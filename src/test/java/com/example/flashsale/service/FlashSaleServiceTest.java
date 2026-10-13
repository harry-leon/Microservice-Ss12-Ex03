package com.example.flashsale.service;

import com.example.flashsale.model.PurchaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashSaleServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RLock rLock;

    @InjectMocks
    private FlashSaleService flashSaleService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Mua hàng thành công khi còn đủ tồn kho")
    void purchaseProduct_Success() throws InterruptedException {
        Long productId = 1L;
        String customerId = "user_01";
        int quantity = 1;

        when(redissonClient.getLock("lock:product:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(valueOperations.get("product:stock:1")).thenReturn("5");

        PurchaseResponse response = flashSaleService.purchaseProduct(productId, customerId, quantity);

        assertTrue(response.isSuccess());
        assertEquals("Đặt hàng thành công!", response.getMessage());
        assertEquals(4, response.getRemainingStock());

        verify(valueOperations).set("product:stock:1", "4");
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("Từ chối mua khi sản phẩm đã hết hàng")
    void purchaseProduct_OutOfStock() throws InterruptedException {
        Long productId = 1L;
        String customerId = "user_02";
        int quantity = 1;

        when(redissonClient.getLock("lock:product:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(valueOperations.get("product:stock:1")).thenReturn("0");

        PurchaseResponse response = flashSaleService.purchaseProduct(productId, customerId, quantity);

        assertFalse(response.isSuccess());
        assertEquals("Sản phẩm đã hết hàng", response.getMessage());
        assertEquals(0, response.getRemainingStock());

        verify(valueOperations, never()).set(eq("product:stock:1"), anyString());
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("Unlock luôn được gọi trong khối finally khi có ngoại lệ")
    void purchaseProduct_UnlockInFinally() throws InterruptedException {
        Long productId = 1L;
        String customerId = "user_03";
        int quantity = 1;

        when(redissonClient.getLock("lock:product:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(valueOperations.get("product:stock:1")).thenThrow(new RuntimeException("Redis connection error"));

        assertThrows(RuntimeException.class, () ->
                flashSaleService.purchaseProduct(productId, customerId, quantity)
        );

        verify(rLock).unlock();
    }

    @Test
    @DisplayName("Mô phỏng 100 luồng đồng thời tranh mua 1 sản phẩm chỉ 1 luồng thành công (chống overselling)")
    void simulateConcurrent100Users_OnlyOneSucceeds() throws InterruptedException {
        Long productId = 100L;
        int initialStock = 1;
        int totalThreads = 100;

        AtomicInteger simulatedRedisStock = new AtomicInteger(initialStock);
        java.util.concurrent.locks.ReentrantLock simulatedLock = new ReentrantLock();

        // Cấu hình mock rLock hoạt động như lock thực tế trong JVM
        when(redissonClient.getLock("lock:product:100")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(inv ->
                simulatedLock.tryLock(5, TimeUnit.SECONDS)
        );
        when(rLock.isHeldByCurrentThread()).thenAnswer(inv -> simulatedLock.isHeldByCurrentThread());
        doAnswer(inv -> {
            simulatedLock.unlock();
            return null;
        }).when(rLock).unlock();

        when(valueOperations.get("product:stock:100")).thenAnswer(inv ->
                String.valueOf(simulatedRedisStock.get())
        );
        doAnswer(inv -> {
            String val = inv.getArgument(1);
            simulatedRedisStock.set(Integer.parseInt(val));
            return null;
        }).when(valueOperations).set(eq("product:stock:100"), anyString());

        ExecutorService executorService = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 1; i <= totalThreads; i++) {
            final String user = "User_" + i;
            executorService.submit(() -> {
                try {
                    startLatch.await(); // Đợi tất cả sẵn sàng để bắn đồng thời
                    PurchaseResponse res = flashSaleService.purchaseProduct(productId, user, 1);
                    if (res.isSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Kích hoạt 100 luồng chạy cùng lúc
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertTrue(completed);
        assertEquals(1, successCount.get(), "Chỉ đúng 1 người mua thành công vì tồn kho = 1");
        assertEquals(99, failCount.get(), "99 người còn lại phải thất bại");
        assertEquals(0, simulatedRedisStock.get(), "Tồn kho cuối cùng phải là 0, không bị bán lố (âm kho)");
    }
}
