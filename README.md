# Musibility

Ứng dụng nghe nhạc Android viết bằng Java, mở trực tiếp bằng Android Studio. Giao diện dùng nền đen `#010101` và xanh chuối `#D7FA00`.

## Bắt đầu

1. Mở thư mục `F:\Musibility` bằng Android Studio, chờ Gradle Sync rồi chạy cấu hình `app` trên thiết bị Android 6.0 trở lên.
2. **Audius là nguồn tìm kiếm và stream chính.** App gọi API công khai Audius với tên ứng dụng `Musibility`; không cần API key, đăng nhập, gói thuê bao hay thiết lập billing cho stream công khai.
3. **Jamendo là nguồn dự phòng.** Nếu Audius không trả kết quả, API không sẵn sàng hoặc stream lỗi, app tự thử Jamendo. Để bật dự phòng này, tạo client ID miễn phí tại [Jamendo Developer Portal](https://developer.jamendo.com/v3.0), rồi thêm vào `local.properties` ở thư mục gốc (giữ nguyên thuộc tính Android SDK mà Android Studio thêm):

   ```properties
   jamendo.clientId=YOUR_FREE_JAMENDO_CLIENT_ID
   ```

   Không cần gói trả phí hoặc thẻ thanh toán cho Audius và catalog Jamendo công khai. Nếu chưa cấu hình Jamendo ID, stream Audius vẫn hoạt động; chỉ nguồn dự phòng Jamendo không khả dụng.

4. Bản đồ và nhận diện bài hát là tùy chọn, không cần thiết lập để nghe nhạc. Nếu muốn bật chúng, có thể thêm:

   ```properties
   google.maps.key=YOUR_GOOGLE_MAPS_ANDROID_KEY
   acrcloud.host=YOUR_ACRCLOUD_HOST
   acrcloud.accessKey=YOUR_ACRCLOUD_ACCESS_KEY
   acrcloud.accessSecret=YOUR_ACRCLOUD_ACCESS_SECRET
   ```

   Các dịch vụ tùy chọn này có hạn mức/điều khoản riêng. Không bật billing hoặc không cấu hình khóa thì chúng không hoạt động, nhưng phát nhạc vẫn dùng được. Không đưa khóa/bí mật vào mã nguồn; `local.properties` đã được loại khỏi Git.

## Tính năng

- Tìm bài hát, nghệ sĩ và playlist; xem các bài nổi bật của nghệ sĩ.
- Bảng bài phổ biến và gợi ý theo nghệ sĩ từ lịch sử nghe gần đây.
- Audius là nguồn chính; nếu tìm kiếm không dùng được hoặc Audius stream gặp lỗi, app tự tìm và phát bản thay thế từ Jamendo khi đã cấu hình Jamendo client ID.
- Tạo playlist cá nhân, phát playlist/album, phát/dừng, tua, chuyển bài, tráo bài, chia sẻ đường dẫn; widget màn hình chính có ảnh bìa, tiến trình và điều khiển.
- Jamendo fallback chỉ chọn track có URL audio và thông tin giấy phép Creative Commons; giấy phép có liên kết từ màn hình phát.
- Lời bài hát từ LRCLIB khi có dữ liệu, đồng bộ khi nguồn cung cấp timed lyrics.
- Nhận diện âm thanh bằng microphone và ACRCloud nếu người dùng tự cấu hình. Android không cho ứng dụng thông thường đọc trực tiếp âm thanh nội bộ đang phát qua loa; hướng microphone về nguồn âm thanh bên ngoài.
- Lưu vị trí nghe tùy chọn và hiển thị trên Google Maps nếu người dùng cấp quyền và cấu hình Maps.

## Dữ liệu và quyền riêng tư

Lịch sử, playlist và vị trí nghe được lưu cục bộ trên thiết bị. Tìm kiếm/stream Audius được gửi tới API Audius; chỉ khi fallback mới gọi Jamendo. Lời bài hát được lấy từ LRCLIB. Âm thanh thu chỉ được gửi tới ACRCloud khi người dùng chủ động chạy nhận diện.
