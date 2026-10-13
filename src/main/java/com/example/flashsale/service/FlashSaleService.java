package com.example.flashsale.service;

import com.example.flashsale.model.PurchaseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlashSaleService {

    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String LOCK_KEY_PREFIX = "lock:product:";

    /**
     * Khởi tạo hoặc cập nhật số lượng tồn kho cho sản phẩm.
     */
    public void setStock(Long productId, int quantity) {
        String stockKey = STOCK_KEY_PREFIX + productId;
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(quantity));
        log.info("Initialized stock for product {} to {}", productId, quantity);
    }

    /**
     * Lấy số lượng tồn kho hiện tại.
     */
    public int getStock(Long productId) {
        String stockKey = STOCK_KEY_PREFIX + productId;
        String val = stringRedisTemplate.opsForValue().get(stockKey);
        return val != null ? Integer.parseInt(val) : 0;
    }

    /**
     * Thực hiện mua hàng với Redis Distributed Lock sử dụng Redisson.
     * Lock key: lock:product:{id}
     *
     * @param productId ID sản phẩm flash sale
     * @param customerId ID khách hàng hoặc tên khách hàng
     * @param quantity số lượng cần mua
     * @return PurchaseResponse kết quả giao dịch
     */
    public PurchaseResponse purchaseProduct(Long productId, String customerId, int quantity) {
        String lockKey = LOCK_KEY_PREFIX + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Chờ tối đa 5 giây để giành khóa, tự giải phóng sau 10 giây nếu service gặp sự cố
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Customer {} could not acquire lock {} in time", customerId, lockKey);
                return PurchaseResponse.builder()
                        .success(false)
                        .message("Hệ thống đang quá tải, vui lòng thử lại sau.")
                        .productId(productId)
                        .remainingStock(getStock(productId))
                        .customerId(customerId)
                        .build();
            }

            try {
                // Đã giành được khóa an toàn: kiểm tra tồn kho và trừ số lượng
                int currentStock = getStock(productId);
                log.info("Customer {} acquired lock {}. Current stock: {}", customerId, lockKey, currentStock);

                if (currentStock < quantity) {
                    log.info("Product {} is out of stock or insufficient for customer {}. Remaining: {}",
                            productId, customerId, currentStock);
                    return PurchaseResponse.builder()
                            .success(false)
                            .message("Sản phẩm đã hết hàng")
                            .productId(productId)
                            .remainingStock(currentStock)
                            .customerId(customerId)
                            .build();
                }

                // Trừ tồn kho trong Redis
                int updatedStock = currentStock - quantity;
                String stockKey = STOCK_KEY_PREFIX + productId;
                stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(updatedStock));

                log.info("Purchase successful for customer {}. Product: {}, bought: {}, remaining: {}",
                        customerId, productId, quantity, updatedStock);

                return PurchaseResponse.builder()
                        .success(true)
                        .message("Đặt hàng thành công!")
                        .productId(productId)
                        .remainingStock(updatedStock)
                        .customerId(customerId)
                        .build();

            } finally {
                // Đảm bảo giải phóng khóa (Unlock) trong khối finally để tránh treo hệ thống
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.info("Released lock {} by customer {}", lockKey, customerId);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread was interrupted while waiting for lock {}", lockKey, e);
            return PurchaseResponse.builder()
                    .success(false)
                    .message("Yêu cầu bị gián đoạn, vui lòng thử lại.")
                    .productId(productId)
                    .remainingStock(getStock(productId))
                    .customerId(customerId)
                    .build();
        }
    }
}
