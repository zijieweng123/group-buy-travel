package com.wzj.infrastructure.dao;

import com.wzj.infrastructure.dao.po.CrowdTags;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author wzj
 * @date 2026-01-12 17:33
 * @description 人群标签dao
 */

@Mapper
public interface ICrowdTagsDao {

    void updateCrowdTagsStatistics(CrowdTags crowdTagsReq);
}
