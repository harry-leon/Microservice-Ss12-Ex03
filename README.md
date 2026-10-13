# [Bài tập] Chống "bán lố" (Overselling) bằng Redis Distributed Lock

## 1. Mục tiêu
- **Kiến thức**: Sử dụng khóa phân tán (Distributed Lock) để bảo vệ tài nguyên dùng chung trong kiến trúc Microservices (Session 11 - Lesson 04).
- **Kỹ năng**: Triển khai Redisson Lock trong môi trường đa luồng / concurrent requests.

---

## 2. Mô tả bài toán
- **Bối cảnh**: Trong các sự kiện Flash Sale, một sản phẩm chỉ còn tồn kho 1 món nhưng có 100 người cùng bấm mua một lúc. Nếu không có cơ chế khóa phân tán, xảy ra tranh chấp Race Condition dẫn đến hệ thống ghi nhận cả 100 đơn hàng thành công (bán lố / overselling).
- **Yêu cầu kỹ thuật**:
  - Sử dụng thư viện **Redisson** để tạo một khóa phân tán với định dạng key: `lock:product:{id}`.
  - Khi khách hàng đặt mua, Service phải giành được khóa này mới được phép kiểm tra tồn kho và trừ số lượng.
  - Đảm bảo giải phóng khóa (`unlock()`) trong khối `finally` để tránh deadlock hoặc treo hệ thống.
- **Kết quả mong muốn**:
  - Chỉ duy nhất số lượng người mua bằng số lượng tồn kho thực tế thành công.
  - Các yêu cầu còn lại nhận thông báo: `"Sản phẩm đã hết hàng"`.

---

## 3. Cấu trúc thư mục dự án

```
Ss12-Ex03/
├── docker-compose.yml
├── pom.xml
├── README.md
└── src
    ├── main
    │   ├── java
    │   │   └── com/example/flashsale
    │   │       ├── FlashSaleApplication.java
    │   │       ├── config
    │   │       │   └── RedissonConfig.java
    │   │       ├── controller
    │   │       │   └── FlashSaleController.java
    │   │       ├── model
    │   │       │   └── PurchaseResponse.java
    │   │       └── service
    │   │           └── FlashSaleService.java
    │   └── resources
    │       └── application.yml
    └── test
        └── java
            └── com/example/flashsale
                └── service
                    └── FlashSaleServiceTest.java
```

---

## 4. Hướng dẫn chạy và kiểm thử

### 4.1. Khởi chạy Redis
Khởi chạy Redis container qua Docker Compose:
```bash
docker compose up -d
```

### 4.2. Chạy ứng dụng Spring Boot
```bash
mvn spring-boot:run
```

### 4.3. API Endpoints
1. **Khởi tạo số lượng tồn kho sản phẩm**:
   - `POST /api/flash-sale/stock?productId=1&quantity=1`
2. **Kiểm tra tồn kho**:
   - `GET /api/flash-sale/stock/1`
3. **Đặt mua sản phẩm (áp dụng Redisson Distributed Lock)**:
   - `POST /api/flash-sale/purchase?productId=1&customerId=user_100&quantity=1`

### 4.4. Chạy kiểm thử tự động (Unit Test & Đa luồng 100 User)
Dự án đã tích hợp bộ kiểm thử đa luồng mô phỏng 100 người dùng bấm mua cùng lúc với sản phẩm chỉ còn 1 món:
```bash
mvn clean test
```
**Kết quả kiểm thử**:
- 1 user mua thành công.
- 99 users nhận thông báo `"Sản phẩm đã hết hàng"`.
- Tồn kho cuối cùng là `0`, tuyệt đối không xảy ra hiện tượng overselling.
