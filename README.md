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

4. Nhận diện bài hát sử dụng AudD. Tạo API token từ [AudD](https://audd.io/) và thêm vào `local.properties` ở thư mục gốc:

   ```properties
   audd.apiToken=YOUR_AUDD_API_TOKEN
   ```

   `local.properties` không được commit. Gradle đưa token vào cấu hình build để app gọi AudD trực tiếp; token có thể được trích xuất từ APK đã phân phối, vì vậy ứng dụng phát hành công khai nên gọi AudD qua backend do bạn kiểm soát.

## Tính năng

- Trang **Khám phá** chia nội dung thành các thẻ tìm kiếm, truy cập nhanh, nhạc thịnh hành và đề xuất; bài hát hiển thị dạng thẻ cuộn ngang.
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
- Nhận diện âm thanh bằng microphone và AudD sau khi cấu hình API token, cấp quyền microphone và có kết nối Internet. App ghi 10 giây âm thanh WAV mono rồi gửi tới AudD qua HTTPS; Android không cho ứng dụng thông thường đọc trực tiếp âm thanh nội bộ đang phát qua loa, vì vậy hãy hướng microphone về nguồn âm thanh bên ngoài.
- Khi nhận diện thành công, app mở trang **Bài hát được nhận diện** với ảnh bìa (nếu AudD cung cấp), tên bài hát và nghệ sĩ; nút **Tìm bài hát này** tra cứu bài hát trong **Khám phá**. Nút X hoặc nút Back của điện thoại đóng kết quả và trở về giao diện nhận diện. Kết quả được giữ khi chuyển tab hoặc rời app nhưng vẫn còn trong danh sách ứng dụng gần đây; xóa Musibility khỏi danh sách đó sẽ đóng phiên và xóa kết quả đang mở.
- Trang **Lịch sử nhận diện** trong giao diện nhận diện lưu tối đa 50 bài hát đã nhận diện gần nhất trên thiết bị. Chọn một mục trong lịch sử để xem lại trang kết quả của bài hát đó.
- Đổi giọng AI qua backend do người dùng cấu hình: kiểm tra kết nối và tải danh sách voice, chọn tệp MP3/WAV/FLAC/M4A/OGG từ máy hoặc chọn nhanh nhạc gần đây, theo dõi tiến trình xử lý, rồi tải và phát kết quả WAV ngay trong app. Tính năng này cần backend tương thích đang chạy trên PC; điện thoại và PC cần kết nối được với nhau qua mạng. Nhập địa chỉ backend (ví dụ `http://192.168.1.20:8000`) trong tab **Đổi giọng**; API key chỉ cần nếu backend yêu cầu. Địa chỉ và API key được lưu trên thiết bị. Kết quả được lưu trong thư mục Tải xuống, hoặc thư mục Tải xuống riêng của ứng dụng nếu không ghi được vào thư mục đó.

### Nghe nhạc đã tải về

1. Mở tab **Tải về**. Lần đầu sử dụng, chọn **Cho phép truy cập nhạc** và cấp quyền đọc âm thanh khi Android hỏi.
2. Musibility quét các tệp âm thanh được Android lập chỉ mục trên thiết bị. Chạm một bài hát trong danh sách để phát bằng các nút điều khiển như nhạc trực tuyến.
3. Sau khi tải hoặc chép thêm bài hát vào máy, nhấn **Làm mới thư viện** để quét lại. Màn hình **Đang phát** sẽ hiển thị các thông tin và ảnh bìa có sẵn trong tệp; thông tin thiếu sẽ không hiện.

## Dữ liệu và quyền riêng tư

Lịch sử nghe, lịch sử nhận diện (tối đa 50 mục) và playlist được lưu cục bộ trên thiết bị. Thư viện nhạc trên thiết bị chỉ được đọc sau khi người dùng cấp quyền âm thanh (Android 13 trở lên yêu cầu quyền nhạc/âm thanh; phiên bản cũ hơn dùng quyền đọc bộ nhớ). Nhạc không được tải lên cho việc phát thông thường; khi người dùng chủ động tạo bản đổi giọng, tệp đã chọn được gửi tới backend do người dùng cấu hình để xử lý. Tìm kiếm/stream Audius được gửi tới API Audius; chỉ khi fallback mới gọi Jamendo. Lời bài hát được lấy từ LRCLIB. Chỉ khi người dùng chủ động chạy nhận diện, app mới ghi 10 giây âm thanh microphone và gửi tệp tới AudD để tra cứu.
