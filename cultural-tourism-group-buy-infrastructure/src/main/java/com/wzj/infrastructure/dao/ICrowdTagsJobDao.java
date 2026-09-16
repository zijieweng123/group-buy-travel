package com.wzj.infrastructure.dao;

import com.wzj.infrastructure.dao.po.CrowdTagsJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author wzj
 * @date 2026-01-12 17:33
 * @description 人群标签任务dao
 */

@Mapper
public interface ICrowdTagsJobDao {

    CrowdTagsJob queryCrowdTagsJob(CrowdTagsJob crowdTagsJobReq);
}
