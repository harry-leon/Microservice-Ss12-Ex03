package com.example.flashsale.controller;

import com.example.flashsale.model.PurchaseResponse;
import com.example.flashsale.service.FlashSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/flash-sale")
@RequiredArgsConstructor
public class FlashSaleController {

    private final FlashSaleService flashSaleService;

    @PostMapping("/stock")
    public ResponseEntity<Map<String, Object>> initStock(
            @RequestParam Long productId,
            @RequestParam int quantity) {
        flashSaleService.setStock(productId, quantity);
        return ResponseEntity.ok(Map.of(
                "productId", productId,
                "stock", quantity,
                "message", "Khởi tạo số lượng tồn kho thành công!"
        ));
    }

    @GetMapping("/stock/{productId}")
    public ResponseEntity<Map<String, Object>> getStock(@PathVariable Long productId) {
        int stock = flashSaleService.getStock(productId);
        return ResponseEntity.ok(Map.of(
                "productId", productId,
                "stock", stock
        ));
    }

    @PostMapping("/purchase")
    public ResponseEntity<PurchaseResponse> purchase(
            @RequestParam Long productId,
            @RequestParam String customerId,
            @RequestParam(defaultValue = "1") int quantity) {
        PurchaseResponse response = flashSaleService.purchaseProduct(productId, customerId, quantity);
        return ResponseEntity.ok(response);
    }
}
