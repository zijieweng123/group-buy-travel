package com.wzj.infrastructure.adapter.repository;

import com.wzj.domain.tag.adapter.repository.ITagRepository;
import com.wzj.domain.tag.model.entity.CrowdTagsJobEntity;
import com.wzj.domain.tag.service.ITagService;
import com.wzj.infrastructure.dao.ICrowdTagsDao;
import com.wzj.infrastructure.dao.ICrowdTagsDetailDao;
import com.wzj.infrastructure.dao.ICrowdTagsJobDao;
import com.wzj.infrastructure.dao.po.CrowdTags;
import com.wzj.infrastructure.dao.po.CrowdTagsDetail;
import com.wzj.infrastructure.dao.po.CrowdTagsJob;
import com.wzj.infrastructure.redis.IRedisService;
import org.redisson.api.RBitSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;

/**
 * @author wzj
 * @date 2026-01-12 17:41
 * @description
 */

@Repository
public class TagRepository implements ITagRepository {

    @Resource
    private ICrowdTagsDao crowdTagsDao;

    @Resource
    private ICrowdTagsDetailDao crowdTagsDetailDao;

    @Resource
    private ICrowdTagsJobDao crowdTagsJobDao;

    @Resource
    private IRedisService  redisService;

    /**
     * 根据tagId和batchId查找人群标签
     * @param tagId
     * @param batchId
     * @return
     */
    @Override
    public CrowdTagsJobEntity queryCrowdTagsJobEntity(String tagId, String batchId) {

        CrowdTagsJob crowdTagsJobReq = new CrowdTagsJob();
        crowdTagsJobReq.setTagId(tagId);
        crowdTagsJobReq.setBatchId(batchId);

        CrowdTagsJob crowdTagsJobRes = crowdTagsJobDao.queryCrowdTagsJob(crowdTagsJobReq);

        return CrowdTagsJobEntity.builder()
                .tagType(crowdTagsJobRes.getTagType())
                .tagRule(crowdTagsJobRes.getTagRule())
                .statStartTime(crowdTagsJobRes.getStatStartTime())
                .statEndTime(crowdTagsJobRes.getStatEndTime())
                .build();
    }

    /**
     * 添加数据
     * @param tagId
     * @param userId
     */
    @Override
    public void addCrowdTagsUserId(String tagId, String userId) {

        CrowdTagsDetail crowdTagsDetailReq = new CrowdTagsDetail();
        crowdTagsDetailReq.setTagId(tagId);
        crowdTagsDetailReq.setUserId(userId);

        try {
            crowdTagsDetailDao.addCrowdTagsUserId(crowdTagsDetailReq);
        } catch (DuplicateKeyException ignore) {
            // 忽略唯一索引冲突
        }

        // 获取BitSet
        RBitSet bitSet = redisService.getBitSet(tagId);
        bitSet.set(redisService.getIndexFromUserId(userId), true);
    }

    /**
     * 更新总量
     * @param tagId
     * @param count
     */
    @Override
    public void updateCrowdTagsStatistics(String tagId, int count) {

        CrowdTags crowdTagsReq = new CrowdTags();
        crowdTagsReq.setTagId(tagId);
        crowdTagsReq.setStatistics(count);

        crowdTagsDao.updateCrowdTagsStatistics(crowdTagsReq);
    }
}
