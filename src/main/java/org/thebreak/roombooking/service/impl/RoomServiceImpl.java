package org.thebreak.roombooking.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.thebreak.roombooking.common.Constants;
import org.thebreak.roombooking.common.exception.CustomException;
import org.thebreak.roombooking.dao.RoomRepository;
import org.thebreak.roombooking.model.Room;
import org.thebreak.roombooking.model.response.CommonCode;
import org.thebreak.roombooking.service.RoomService;

import java.util.List;

@Service
public class RoomServiceImpl implements RoomService {
    @Autowired
    RoomRepository repository;

    public Room add(Room room) {
        if(room.getName() == null || room.getType() == null){
            CustomException.cast(CommonCode.INVALID_PARAM);
        }
        if(repository.findByName(room.getName()) != null){
            CustomException.cast(CommonCode.DB_ENTRY_ALREADY_EXIST);
        }
        return repository.save(room);
    }

    public List<Room> findPage(int page, int size) {
        // mongo page start with 0;

        if(page < 1 ){
            page = 1;
        }
        if(size > Constants.MAX_PAGE_SIZE){
            size = Constants.MAX_PAGE_SIZE;
        }
        page = page -1;

        Page<Room> roomPage = repository.findAll(PageRequest.of(page, size, Sort.by("name").ascending()));
        return roomPage.getContent();
    }
}