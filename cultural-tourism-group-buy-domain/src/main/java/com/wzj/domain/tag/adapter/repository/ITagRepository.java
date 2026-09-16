package com.wzj.domain.tag.adapter.repository;

import com.wzj.domain.tag.model.entity.CrowdTagsJobEntity;

/**
 * @author wzj
 * @date 2026-01-12 17:44
 * @description
 */

public interface ITagRepository {

    CrowdTagsJobEntity queryCrowdTagsJobEntity(String tagId, String batchId);

    void addCrowdTagsUserId(String tagId, String userId);

    void updateCrowdTagsStatistics(String tagId, int count);

}
