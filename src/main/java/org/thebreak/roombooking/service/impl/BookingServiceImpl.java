package org.thebreak.roombooking.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thebreak.roombooking.common.AvailableTypeEnum;
import org.thebreak.roombooking.common.BookingStatusEnum;
import org.thebreak.roombooking.common.Constants;
import org.thebreak.roombooking.common.exception.CustomException;
import org.thebreak.roombooking.common.response.CommonCode;
import org.thebreak.roombooking.common.util.BookingUtils;
import org.thebreak.roombooking.dao.BookingRepository;
import org.thebreak.roombooking.dao.RoomRepository;
import org.thebreak.roombooking.model.Booking;
import org.thebreak.roombooking.model.BookingTimeRange;
import org.thebreak.roombooking.model.Room;
import org.thebreak.roombooking.model.bo.BookingBO;
import org.thebreak.roombooking.model.vo.BookingPreviewVO;
import org.thebreak.roombooking.service.BookingService;
import org.thebreak.roombooking.service.MailSenderService;
import org.thebreak.roombooking.service.RoomService;

import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class BookingServiceImpl implements BookingService {
    @Autowired
    private BookingRepository repository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MailSenderService mailSenderService;

    @Override
    @Transactional
    public BookingPreviewVO add(BookingBO bookingBO) {
        checkBookingBoEmptyOrNull(bookingBO);

        // find room detail by id
        Optional<Room> optionalRoom = roomRepository.findById(bookingBO.getRoomId());
        if(!optionalRoom.isPresent()){
            CustomException.cast(CommonCode.DB_ENTRY_NOT_FOUND);
        }
        Room room = optionalRoom.get();

        // validate booking times
        // get current time in UTC, and convert to same room zoned time;
        LocalDateTime bookedAtUTC = LocalDateTime.now(ZoneId.of("UTC"));

        long totalBookedHours = 0;

        List<BookingTimeRange> bookingTimeList = bookingBO.getBookingTime();
        for (BookingTimeRange bookingTimeRange : bookingTimeList) {

            LocalDateTime start = bookingTimeRange.getStart();
            LocalDateTime end = bookingTimeRange.getEnd();

            // 0. check end time must later than start time
            if(!end.isAfter(start)){
                CustomException.cast(CommonCode.BOOKING_END_BEFORE_START);
            }

            // 1. check start or end time must later than current time
            if(!BookingUtils.checkBookingTimeAfterNow(start, room.getCity())){
                CustomException.cast(CommonCode.BOOKING_TIME_EARLIER_THAN_NOW);
            }
            if(!BookingUtils.checkBookingTimeAfterNow(end, room.getCity())){
                CustomException.cast(CommonCode.BOOKING_TIME_EARLIER_THAN_NOW);
            }


            // 2. check start or end time matches available type (weekend or weekday)
            boolean isWeekendType = room.getAvailableType().equals(AvailableTypeEnum.WEEKEND.getDescription());
            if(isWeekendType){
                // cast exception if type is weekend but booking time is weekday
                 if(!BookingUtils.checkIsWeekend(start) || !BookingUtils.checkIsWeekend(end)){
                     CustomException.cast(CommonCode.BOOKING_WEEKEND_ONLY);
                 }
            } else {
                // cast exception if type is weekday but booking time is weekend
                if(BookingUtils.checkIsWeekend(start) || BookingUtils.checkIsWeekend(end)){
                    CustomException.cast(CommonCode.BOOKING_WEEKDAY_ONLY);
                }
            };

            // 3. check start or end time must be in quarter
            if(!BookingUtils.checkTimeIsQuarter(start) || !BookingUtils.checkTimeIsQuarter(end)){
                CustomException.cast(CommonCode.BOOKING_TIME_QUARTER_ONLY);
            }

            // 4. check booking time must be hourly and at least one hour
            if(!BookingUtils.checkDurationInHour(start, end)){
                CustomException.cast(CommonCode.BOOKING_TIME_HOURLY_ONLY);
            }

            // 5. check booking time has not been booked by other users.
            List<BookingTimeRange> futureBookedTimesByRoom = findFutureBookedTimesByRoom(room.getId(),room.getCity());
            for (BookingTimeRange timeRange : futureBookedTimesByRoom) {
                // check start time is not between the booked time
                if(start.isAfter(timeRange.getStart()) && start.isBefore(timeRange.getEnd())){
                    CustomException.cast(CommonCode.BOOKING_TIME_ALREADY_TAKEN);
                }
                // check end time is not between the booked time
                if(end.isAfter(timeRange.getStart()) && end.isBefore(timeRange.getEnd())){
                    CustomException.cast(CommonCode.BOOKING_TIME_ALREADY_TAKEN);
                }
                // check the booking time range is not covering any booked time
                if(start.isBefore(timeRange.getStart()) && end.isAfter(timeRange.getEnd())){
                    CustomException.cast(CommonCode.BOOKING_TIME_ALREADY_TAKEN);
                }
                // check the booking time range is not inside any other booked time
                if(start.isAfter(timeRange.getStart()) && end.isBefore(timeRange.getEnd())){
                    CustomException.cast(CommonCode.BOOKING_TIME_ALREADY_TAKEN);
                }
                // check the booking time is exactly the same as any other booked time
                if(start.isEqual(timeRange.getStart()) || end.isEqual(timeRange.getEnd())){
                    CustomException.cast(CommonCode.BOOKING_TIME_ALREADY_TAKEN);
                }
            }

            // get hourly duration of each booking
            long bookingHours = BookingUtils.getBookingHours(start, end);
            totalBookedHours += bookingHours;

        }

        Long totalAmount = room.getPrice() * totalBookedHours;
        String userId = "userId001";
        String status = BookingStatusEnum.UNPAID.getDescription();

        Booking booking = new Booking();
        booking.setBookedAt(bookedAtUTC);
        booking.setTotalHours(totalBookedHours);
        booking.setTotalAmount(totalAmount);
        booking.setPaidAmount(0L);
        booking.setRoom(room);
        booking.setStatus(status);
        booking.setUserId(userId);
        booking.setContact(bookingBO.getContact());
        booking.setRemark(bookingBO.getRemark());
        booking.setBookedTime(bookingTimeList);



        Booking booking1 = repository.save(booking);

        // send email notification
       log.info("BookingServiceImpl: start to send email.");

        try {
            mailSenderService.sendBookingConfirmEmailNotification(bookingBO.getContact().getEmail(),
                    userId, room.getTitle(),bookingBO.getBookingTime().get(0).getStart(),totalBookedHours,totalAmount);
        } catch (MessagingException e){
//            CustomException.cast(CommonCode.BOOKING_SENDEMAIL_FAILED);
            log.error("serviceImpl email exception");
            log.error(e.getMessage());
        }

        // TODO implement Async email notification with MQ or other solution;

        BookingPreviewVO bookingPreviewVO = new BookingPreviewVO();

        BeanUtils.copyProperties(booking1, bookingPreviewVO);
        bookingPreviewVO.setRoomCity(room.getCity());
        bookingPreviewVO.setRoomTitle(room.getTitle());

        return bookingPreviewVO;
    }

    private void checkBookingBoEmptyOrNull(BookingBO bookingBO) {
        if(bookingBO == null){
            CustomException.cast(CommonCode.REQUEST_JSON_MISSING);
        }
        if(bookingBO.getRoomId() == null){
            CustomException.cast(CommonCode.REQUEST_ID_FIELD_MISSING);
        }
        if(bookingBO.getRoomId().isEmpty()){
            CustomException.cast(CommonCode.REQUEST_ID_INVALID_OR_EMPTY);
        }
        if(bookingBO.getBookingTime() == null){
            CustomException.cast(CommonCode.REQUEST_FIELD_MISSING);
        }
        if(bookingBO.getBookingTime().isEmpty()){
            CustomException.cast(CommonCode.REQUEST_FIELD_EMPTY);
        }
        if(bookingBO.getContact() == null){
            CustomException.cast(CommonCode.BOOKING_CONTACT_NOTNULL);
        }

        if(bookingBO.getContact().getEmail() == null || bookingBO.getContact().getMobile() == null || bookingBO.getContact().getName() == null){
            CustomException.cast(CommonCode.BOOKING_CONTACT_NOTNULL);
        }

        try {
            // use java mail to validate email format
            InternetAddress emailAddr = new InternetAddress(bookingBO.getContact().getEmail());
            emailAddr.validate();
        } catch (AddressException ex) {
            CustomException.cast(CommonCode.BOOKING_EMAIL_INVALID);
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public List<BookingTimeRange> findFutureBookedTimesByRoom(String roomId, String city) {
        if(city == null){
            Room room = roomService.findById(roomId);
            city = room.getCity();
        }

        LocalDateTime nowAtZonedCity = BookingUtils.getNowAtZonedCity(city);
        LocalDateTime after7days = nowAtZonedCity.plusDays(7);

        Query query = new Query();
        query.addCriteria(Criteria.where("room.id").is(roomId))
                .addCriteria(Criteria.where("bookedTime").elemMatch(Criteria.where("end").gt(nowAtZonedCity)))
                .fields().include("bookedTime");

        List<Booking> list = mongoTemplate.find(query, Booking.class);

        List<BookingTimeRange> futureBookedTimeList = new ArrayList<>();

        for (Booking booking : list) {
            List<BookingTimeRange> timeList = booking.getBookedTime();
            for (BookingTimeRange bookingTimeRange : timeList) {
                LocalDateTime end = bookingTimeRange.getEnd();
                if(end.isAfter(nowAtZonedCity)){
                    futureBookedTimeList.add(bookingTimeRange);
                }
            }
        }

        return futureBookedTimeList;
    }

    @Override
    public Booking findById(String id) {
        if(id == null){
            CustomException.cast(CommonCode.REQUEST_FIELD_MISSING);
        }
        if(id.trim().isEmpty()){
            CustomException.cast(CommonCode.REQUEST_FIELD_EMPTY);
        }
        Optional<Booking> optional = repository.findById(id);
        if(!optional.isPresent()){
            CustomException.cast(CommonCode.DB_ENTRY_NOT_FOUND);
        }
        return optional.get();
    }

    @Override
    public Page<Booking> findPage(Integer page, Integer size) {
        if(page == null || page < 1){
            page = 1;
        }
        // mongo page start with 0;
        page = page -1;

        if(size == null){
            size = Constants.DEFAULT_PAGE_SIZE;
        }
        if(size > Constants.MAX_PAGE_SIZE){
            size = Constants.MAX_PAGE_SIZE;
        }

        Page<Booking> bookingsPage = repository.findAll(PageRequest.of(page, size, Sort.by("updatedAt").descending()));

        if(bookingsPage.getContent().size() == 0 ){
            CustomException.cast(CommonCode.DB_EMPTY_LIST);
        }
        return bookingsPage;
    }

    @Override
    public Page<Booking> findPageByUser(String userId, Integer page) {
        if(page == null || page < 1){
            page = 1;
        }
        // mongo page start with 0;
        page = page -1;

        Pageable pageable = PageRequest.of(page, Constants.DEFAULT_PAGE_SIZE, Sort.by("updatedAt").descending());

        Page<Booking> bookingsPage = repository.findByUserId(userId, pageable);

        if(bookingsPage.getContent().size() == 0 ){
            CustomException.cast(CommonCode.DB_EMPTY_LIST);
        }

        return bookingsPage;
    }

    @Override
    public Page<Booking> findPageActiveBookings(Integer page, Integer size) {
        if(page == null || page < 1){
            page = 1;
        }
        // mongo page start with 0;
        page = page -1;

        if(size == null){
            size = Constants.DEFAULT_PAGE_SIZE;
        }
        if(size > Constants.MAX_PAGE_SIZE){
            size = Constants.MAX_PAGE_SIZE;
        }


        Query query = new Query();
        query.addCriteria(Criteria.where("status").in("Paid", "Unpaid"));

        Pageable pageable = PageRequest.of(page, size, Sort.by("bookedAt").descending());

        List<Booking> list = mongoTemplate.find(query, Booking.class);
        // MongoTemplate does not have built in Page, have to use PageableExecutionUtils from spring data
        Page<Booking> bookingsPage = PageableExecutionUtils.getPage(
                list,
                pageable,
                () -> mongoTemplate.count(query, Booking.class));

        return bookingsPage;
    }

    @Override
    public void deleteById(String id) {
        if(id == null) {
            CustomException.cast(CommonCode.REQUEST_FIELD_MISSING);
        }
        if(id.trim().isEmpty()){
            CustomException.cast(CommonCode.REQUEST_FIELD_EMPTY);
        }
        Optional<Booking> optional = repository.findById(id);
        if(optional.isPresent()){
            repository.deleteById(id);
        } else {
            CustomException.cast(CommonCode.DB_ENTRY_NOT_FOUND);
        }
    }


    @Override
    public Booking updateById(Booking booking) {
        if(booking == null){
            CustomException.cast(CommonCode.REQUEST_FIELD_MISSING);
        }
        if(null == booking.getId()){
            CustomException.cast(CommonCode.REQUEST_FIELD_MISSING);
        }

        Optional<Booking> optional = repository.findById(booking.getId());
        if(!optional.isPresent()){
            CustomException.cast(CommonCode.DB_ENTRY_NOT_FOUND);
        };

        // TODO to implement update ignore null fields
        Booking roomReturn = optional.get();

        return repository.save(booking);

    }

    @Override
    public Booking updateStatusById(String id, String status, Long paidAmount) {
        if(id == null || status == null){
            CustomException.cast(CommonCode.REQUEST_FIELD_MISSING);
        }
        if(status.trim().isEmpty()){
            CustomException.cast(CommonCode.REQUEST_FIELD_MISSING);
        }

        // TODO check status param is one of the status constant;
        String capitalizedStatus = BookingUtils.getStringCapitalized(status);

        // if status to be paid, need to provide amount > 0
        if(capitalizedStatus.equals("Paid") && paidAmount == null || paidAmount <= 0){
            CustomException.cast(CommonCode.BOOKING_PAID_WITHOUT_AMOUNT);
        }

        Query query = new Query().addCriteria(Criteria.where("id").is(id));
        Update update = new Update().set("status", capitalizedStatus);
        if(capitalizedStatus.equals("Paid")){
            update.set("paidAmount", paidAmount);
        }
        Booking updatedBooking = mongoTemplate.update(Booking.class)
                .matching(query)
                .apply(update)
                .withOptions(FindAndModifyOptions.options().returnNew(true))
                .findAndModifyValue();


        return updatedBooking;

    }
}
