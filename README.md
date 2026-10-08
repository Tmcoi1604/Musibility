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

4. Nhận diện bài hát là tùy chọn, không cần thiết lập để nghe nhạc. Nếu muốn bật, tạo tài khoản tại [ACRCloud](https://console.acrcloud.com/) và tạo project nhận diện **Audio & Video Recognition**. Lấy Host, Access Key và Access Secret trong thông tin project, rồi thêm vào `local.properties`:

   ```properties
   acrcloud.host=YOUR_ACRCLOUD_HOST
   acrcloud.accessKey=YOUR_ACRCLOUD_ACCESS_KEY
   acrcloud.accessSecret=YOUR_ACRCLOUD_ACCESS_SECRET
   ```

   Chỉ dùng hostname cho `acrcloud.host` (không thêm `https://` hay đường dẫn). ACRCloud có hạn mức/điều khoản riêng; nếu không cấu hình thì nhận diện không hoạt động, nhưng phát nhạc vẫn dùng được. Không đưa khóa/bí mật vào mã nguồn; `local.properties` đã được loại khỏi Git.

## Tính năng

- Tìm bài hát, nghệ sĩ và playlist trong một lần tìm kiếm; xem các bài nổi bật của nghệ sĩ.
- Bảng bài phổ biến và gợi ý theo nghệ sĩ từ lịch sử nghe gần đây.
- Audius là nguồn chính; nếu tìm kiếm không dùng được hoặc Audius stream gặp lỗi, app tự tìm và phát bản thay thế từ Jamendo khi đã cấu hình Jamendo client ID.
- Chạm một bài trong danh sách chọn bài để tự động phát và mở giao diện **Đang phát**. Duyệt và phát nhạc đã tải/lưu trên thiết bị từ tab **Tải về**; dùng **Làm mới thư viện** sau khi tải thêm nhạc.
- Màn hình phát hiển thị ảnh bìa và metadata đọc được từ tệp nhạc: nghệ sĩ album, thể loại, năm phát hành, số thứ tự, thời lượng, tên và thư mục tệp, định dạng, dung lượng, bitrate. Trường nào không có trong tệp sẽ được bỏ qua.
- Giao diện phát nhạc dùng icon cho các nút điều khiển, thêm playlist, chia sẻ và lời bài hát. Nút Back trên giao diện phát (hoặc nút Back của điện thoại) thu nhỏ trình phát thành mini-player phía trên thanh điều hướng và quay về **Khám phá**. Mini-player luôn có sẵn ở các màn hình khác khi đang có bài hát, cho phép mở lại giao diện phát, phát/tạm dừng hoặc thêm bài vào playlist. Các mục ở thanh điều hướng có icon riêng.
- Có thể tải bài hát trực tuyến từ giao diện phát nhạc; tệp được lưu trong thư mục nhạc riêng của Musibility và xuất hiện trong tab **Tải về**.
- Giao diện phát nhạc tự phối nền chuyển sắc theo màu ảnh bìa album. Điều khiển media của Android hỗ trợ phát/tạm dừng, chuyển bài và tua khi thiết bị hiển thị thanh tiến trình.
- Tạo playlist cá nhân, phát playlist/album, phát/dừng, tua, chuyển bài, tráo bài, chia sẻ đường dẫn; widget màn hình chính dạng thẻ ngang có ảnh bìa, tên bài hát, nghệ sĩ, tiến trình và nút điều khiển.
- Jamendo fallback chỉ chọn track có URL audio và thông tin giấy phép Creative Commons; giấy phép có liên kết từ màn hình phát.
- Lời bài hát từ LRCLIB khi có dữ liệu, đồng bộ khi nguồn cung cấp timed lyrics.
- Nhận diện âm thanh bằng microphone và ACRCloud nếu người dùng tự cấu hình. Android không cho ứng dụng thông thường đọc trực tiếp âm thanh nội bộ đang phát qua loa; hướng microphone về nguồn âm thanh bên ngoài.

### Nghe nhạc đã tải về

1. Mở tab **Tải về**. Lần đầu sử dụng, chọn **Cho phép truy cập nhạc** và cấp quyền đọc âm thanh khi Android hỏi.
2. Musibility quét các tệp âm thanh được Android lập chỉ mục trên thiết bị. Chạm một bài hát trong danh sách để phát bằng các nút điều khiển như nhạc trực tuyến.
3. Sau khi tải hoặc chép thêm bài hát vào máy, nhấn **Làm mới thư viện** để quét lại. Màn hình **Đang phát** sẽ hiển thị các thông tin và ảnh bìa có sẵn trong tệp; thông tin thiếu sẽ không hiện.

## Dữ liệu và quyền riêng tư

Lịch sử nghe và playlist được lưu cục bộ trên thiết bị. Thư viện nhạc trên thiết bị chỉ được đọc sau khi người dùng cấp quyền âm thanh (Android 13 trở lên yêu cầu quyền nhạc/âm thanh; phiên bản cũ hơn dùng quyền đọc bộ nhớ); ứng dụng không tải tệp nhạc lên. Tìm kiếm/stream Audius được gửi tới API Audius; chỉ khi fallback mới gọi Jamendo. Lời bài hát được lấy từ LRCLIB. Âm thanh thu chỉ được gửi tới ACRCloud khi người dùng chủ động chạy nhận diện.
