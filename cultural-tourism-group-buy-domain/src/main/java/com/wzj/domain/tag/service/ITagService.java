package com.wzj.domain.tag.service;

/**
 * @author wzj
 * @date 2026-01-12 17:44
 * @description 人群标签服务接口
 */
public interface ITagService {

    /**
     * 执行人群标签批次任务
     *
     * @param tagId   人群ID
     * @param batchId 批次ID
     */
    void execTagBatchJob(String tagId, String batchId);

}
