package com.wzj.infrastructure.adapter.repository;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.wzj.domain.activity.model.valobj.SkuVO;
import com.wzj.infrastructure.dao.ISkuDao;
import com.wzj.infrastructure.dao.po.Sku;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Bounded, short-lived catalog metadata. Order and occupied-stock state stay uncached. */
@Component
public class SkuCatalogCache {
    private final ISkuDao skuDao;
    private final boolean enabled;
    private final Cache<String, Optional<Sku>> cache;

    public SkuCatalogCache(ISkuDao skuDao,
                           @Value("${trade.sku-cache-enabled:true}") boolean enabled,
                           @Value("${trade.sku-cache-ttl-ms:5000}") long ttlMillis,
                           @Value("${trade.sku-cache-max-size:1024}") long maximumSize) {
        if (ttlMillis < 1 || maximumSize < 1) throw new IllegalArgumentException("Invalid catalog cache bounds");
        this.skuDao = skuDao;
        this.enabled = enabled;
        this.cache = CacheBuilder.newBuilder().maximumSize(maximumSize)
                .expireAfterWrite(ttlMillis, TimeUnit.MILLISECONDS).build();
    }

    public SkuVO query(String goodsId, boolean cacheAllowed) {
        Sku sku;
        if (!enabled || !cacheAllowed) {
            sku = skuDao.querySkuByGoodsId(goodsId);
        } else {
            try {
                // Guava coalesces concurrent loads for the same key; absent goods are bounded too.
                sku = cache.get(goodsId, () -> Optional.ofNullable(skuDao.querySkuByGoodsId(goodsId)))
                        .orElse(null);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Cannot load SKU catalog", failure.getCause());
            }
        }
        // Do not expose the mutable cached PO or share mutable response objects.
        return sku == null ? null : SkuVO.builder().goodsId(sku.getGoodsId())
                .goodsName(sku.getGoodsName()).originalPrice(sku.getOriginalPrice()).build();
    }

    /** A catalog write on this instance should invalidate after the database commit. */
    public void invalidate(String goodsId) {
        cache.invalidate(goodsId);
    }
}
