package org.thebreak.roombooking.controller;

import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.thebreak.roombooking.model.response.PageResult;
import org.thebreak.roombooking.model.response.ResponseResult;
import org.thebreak.roombooking.model.Dictionary;
import org.thebreak.roombooking.model.vo.DictionaryVO;
import org.thebreak.roombooking.service.DictionaryService;

import java.util.ArrayList;
import java.util.List;

@RestController
@CrossOrigin
@Slf4j
@RequestMapping(value = "api/v1/dict")
public class DictionaryController {
    @Autowired
    private DictionaryService dictionaryService;


    @PostMapping(value = "/add")
    public ResponseResult<Dictionary> addDictionary(@RequestParam String name){
        log.debug("From logger : addDictionary controller called.");
        Dictionary d = dictionaryService.addDictionary(name);
        return ResponseResult.success(d);
    }

    @GetMapping(value = "")
    public ResponseResult<PageResult<List<DictionaryVO>>> findPage(
            @RequestParam Integer page,
            @RequestParam Integer size){

        log.info("From logger : addDictionary controller called.");

        Page<Dictionary> dictPage = dictionaryService.findPage(page, size);
        List<DictionaryVO> voList = new ArrayList<>();
        for (Dictionary dictionary : dictPage.getContent()) {
            DictionaryVO dictionaryVO = new DictionaryVO();
            BeanUtils.copyProperties(dictionary, dictionaryVO);
            voList.add(dictionaryVO);
        }

        // assemble page result with page info
        PageResult<List<DictionaryVO>> pageResult = new PageResult<>();
        pageResult.setTotalRows(dictPage.getTotalElements());
        pageResult.setPageSize(dictPage.getSize());                // request page size
        pageResult.setTotalPages(dictPage.getTotalPages());
        pageResult.setContentSize(dictPage.getNumberOfElements());  // actual content size returned
        pageResult.setCurrentPage(dictPage.getNumber() + 1);       // mongo start with 0, so need to add 1
        pageResult.setContent(voList);

        return ResponseResult.success(pageResult);
    }

    @GetMapping(value = "/byName")
    public ResponseResult<DictionaryVO> findByName(@RequestParam String name){
        Dictionary dictionary = dictionaryService.findByName(name);
        DictionaryVO dictionaryVO = new DictionaryVO();
        BeanUtils.copyProperties(dictionary, dictionaryVO);
        return ResponseResult.success(dictionaryVO);
    }

    @GetMapping(value = "/byId")
    public ResponseResult<Dictionary> findById(@RequestParam String id){
        return ResponseResult.success(dictionaryService.findById(id));
    }

    @PostMapping(value = "/update")
    public ResponseResult<Dictionary> update(@RequestParam String id, String name){
        return ResponseResult.success(dictionaryService.updateById(id, name));
    }
    @PostMapping(value = "/delete")
    public ResponseResult<Dictionary> deleteById(@RequestParam String id){
        dictionaryService.deleteById(id);
        return ResponseResult.success();
    }

    @PostMapping(value = "/addValue")
    public ResponseResult<DictionaryVO> addValue(@RequestParam String id, String value){
        DictionaryVO dictionaryVO = new DictionaryVO();
        BeanUtils.copyProperties(dictionaryService.addValueById(id, value), dictionaryVO);
        return ResponseResult.success(dictionaryVO);
    }

    @PostMapping(value = "/deleteValue")
    public ResponseResult<DictionaryVO> deleteValue(@RequestParam String id, String value){
        DictionaryVO dictionaryVO = new DictionaryVO();
        BeanUtils.copyProperties(dictionaryService.deleteValue(id, value), dictionaryVO);
        return ResponseResult.success(dictionaryVO);
    }

}
