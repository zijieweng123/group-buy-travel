package com.wzj.infrastructure.dao;

import com.wzj.infrastructure.dao.po.CrowdTagsDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author wzj
 * @date 2026-01-12 17:33
 * @description 人群标签明细dao
 */

@Mapper
public interface ICrowdTagsDetailDao {

    void addCrowdTagsUserId(CrowdTagsDetail crowdTagsDetailReq);
}
