# JobBeacon crawler

Service crawl tin tuyển dụng bằng Playwright rồi publish lên RabbitMQ (exchange `job-posting`, queue `job-posting.parsed`). `Jobbeacon-be` đọc queue đó và lưu job.

Mỗi trang job được trích xuất bằng hai cách rồi gộp lại:

- **JSON-LD** (`<script type="application/ld+json">` kiểu `JobPosting`) là nguồn chính.
- **HTML** theo XPath của domain, dùng để bù các trường JSON-LD thiếu. Skills lấy hợp của hai nguồn.

XPath của mỗi domain nằm trong bảng `domain_extractor_config`, không có trong `application.yaml`. Domain chưa có XPath sẽ được module structure discovery tự tìm bằng Gemini.

## Chạy

Cần Postgres (DB `job_crawler`), Redis có module RedisBloom, RabbitMQ. Compose ở `jobbeacon-deployment`:

```bash
cd ../jobbeacon-deployment
docker compose up -d postgres redis rabbitmq
```

Rồi chạy crawler. Liquibase tạo bảng khi khởi động:

```bash
EUREKA_ENABLED=false ./mvnw spring-boot:run
```

Health: `http://localhost:8081/job-posting-crawler/actuator/health` (context path `/job-posting-crawler`).

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/job_crawler`, `postgres`, `postgres` | DB của crawler |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6379`, trống | Hàng đợi URL, Bloom filter URL đã crawl |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `guest`, `guest` | Nơi publish tin |
| `PROFILE_ACTIVE` | `local` | `application-local.yaml` ghi đè danh sách source |
| `CRAWLER_WORKER_POOL_SIZE`, `CRAWLER_MAX_DEPTH` | `1`, `5` | Số luồng crawl, độ sâu link tối đa |
| `PLAYWRIGHT_HEADLESS`, `PLAYWRIGHT_PROXIES` | `false`, trống | Trình duyệt của crawler |
| `EUREKA_ENABLED`, `EUREKA_URL` | `true`, `http://localhost:8761/eureka/` | Đăng ký discovery |
| `STRUCTURE_DISCOVERY_ENABLED` | `false` | Bật tự tìm XPath cho domain chưa có cấu hình |
| `GEMINI_API_KEY` | trống | Một hoặc nhiều key, cách nhau bằng dấu phẩy |
| `GEMINI_MODEL` | `gemini-3.1-flash-lite` | Model dùng cho structure discovery |

Danh sách source (`crawler.sources`: seed, `job-page-url-prefix`, delay) nằm trong `application.yaml` và `application-local.yaml`.

## Bảng `domain_extractor_config`

Mỗi domain một dòng. `domain_key` là host bỏ `www.`, dấu chấm thay bằng gạch dưới (`topcv.vn` thành `topcv_vn`).

| Cột | Ý nghĩa |
|---|---|
| `status` | `RUNNING` (đang discover), `ACTIVE` (crawler dùng `xpaths`), `FAILED` (lỗi, chờ thử lại), `STALE` (site đổi giao diện, chờ discover lại) |
| `field_coverage` | Tỉ lệ trang mẫu có từng trường lúc discover, ví dụ `{"title": 1.0, "skills": 0.5}`. Làm mốc để phát hiện XPath hỏng |
| `stale_fields`, `stale_since` | Các trường có XPath đã hỏng (crawler không dùng nữa), và thời điểm domain chuyển `STALE` |
| `xpaths` | JSON `{"title": "//h1", "companyName": "...", ...}`. Khoá: `title`, `companyName`, `companyLogoUrl`, `description`, `experienceMonths`, `employmentType`, `postedDate`, `expiredDate`, `location`, `salary`, `industry`, `skills`, `benefits` |
| `report`, `warnings`, `sample_urls` | Kết quả của lần discover: giá trị trích được trên từng trang mẫu, cảnh báo (XPath dễ vỡ, thiếu trên một số trang...) |
| `error`, `attempts`, `next_retry_at` | Lỗi gần nhất, số lần đã chạy, giờ thử lại |

ITviec có sẵn dòng `ACTIVE` với XPath viết tay (`db/changelog/data/001_itviec_extractor_config.sql`).

Cách viết XPath:

- Thêm `/@src` ở cuối để lấy thuộc tính (logo).
- XPath khớp nhiều phần tử thì các giá trị được nối bằng `|` (BE tách location theo `|`).
- Với `skills` và `industry`, XPath trỏ vào phần tử **bao danh sách**, mỗi con trực tiếp là một mục. Nếu phần tử không có con (`<p>QA, Training</p>`), text của nó được tách theo dấu phẩy.

Crawler đọc bảng này vào bộ nhớ và làm mới mỗi 5 phút (`crawler.extractor.config-refresh-interval`). Sửa bằng SQL thì có hiệu lực sau tối đa 5 phút.

## Structure discovery

Bật bằng `STRUCTURE_DISCOVERY_ENABLED=true` và `GEMINI_API_KEY`. Thiếu key thì module tắt, log một cảnh báo, và crawler chạy như khi tắt.

Khi app khởi động và sau đó mỗi giờ, mỗi source đang bật được kiểm tra:

| Trạng thái domain | Discovery | Crawler |
|---|---|---|
| Chưa có dòng | Chạy | **Tạm dừng** domain cho tới khi lần chạy đầu xong |
| `RUNNING`, lần đầu | Chạy lại nếu quá 30 phút (app tắt giữa chừng) | Tạm dừng |
| `RUNNING`, lần thử lại | Như trên | Crawl, chỉ dùng JSON-LD |
| `ACTIVE` | Không chạy | Crawl, gộp JSON-LD và HTML |
| `FAILED` | Thử lại sau 1h, 2h, 4h, 8h, 16h, rồi mỗi 24h | Crawl, chỉ dùng JSON-LD |

Discover xong mà tìm được ít nhất một trường thì dòng thành `ACTIVE` ngay, không cần duyệt. Seed của domain được đẩy lại vào hàng đợi khi mỗi lần chạy kết thúc.

Một lần chạy mất khoảng 1–2 phút mỗi domain, các domain chạy lần lượt trên một luồng riêng:

1. Mở seed, gom link theo dạng URL, lấy 4 trang mẫu. Nếu LLM nói các trang mẫu không phải tin tuyển dụng thì thử nhóm link khác. Nếu seed không có link job nào (trang chủ tải danh sách sau) thì mở tối đa 3 trang danh sách cùng domain.
2. Rút gọn HTML (khoảng 1,5 triệu ký tự còn dưới 40 nghìn), gửi 2 trang cho Gemini chọn phần tử chứa từng trường.
3. Sinh XPath từ phần tử đó trên DOM gốc (theo nhãn, id, class...), chấm điểm trên cả 4 trang, chọn XPath ổn định nhất.

Nhiều key Gemini được xoay giống `Jobbeacon-be`:

- 429 hoặc key sai/hết hạn (401, 403, 400 "API key not valid"): chuyển ngay sang key kế tiếp.
- 5xx, timeout: thử lại trên cùng key (chờ 2s, rồi 4s).
- Mọi key đều 429: chờ 15s, rồi 30s.

Quota free tier tính theo project Google Cloud, nên các key nên thuộc các project khác nhau.

Thiết lập trong `crawler.structure-discovery`: `sample-count`, `llm-sample-count`, `page-settle-time`, `recheck-interval`, `running-timeout`, `retry-base-delay`, `retry-max-delay`.

## Phát hiện site đổi giao diện

Phần này luôn bật (`crawler.extractor.health.enabled`), kể cả khi tắt discovery. Nó dùng chính các trang crawler đang crawl, không request thêm và không gọi LLM.

1. Mỗi trang job của domain `ACTIVE`/`STALE`, mỗi trường có XPath được chấm là **hợp lệ** hay không. Hợp lệ nghĩa là có giá trị và đúng dạng của trường: ngày và lương parse được ("Thỏa thuận" cũng tính), title dài 5–200 ký tự và khác tên công ty, mô tả dài hơn 100 ký tự, danh sách có 1–30 mục...
2. Title và tên công ty còn được so với JSON-LD theo kiểu gần đúng: bỏ dấu, không phân biệt hoa thường, chuỗi này chứa chuỗi kia hoặc có ≥ 60% từ chung. Các trường khác không so, vì JSON-LD và HTML viết khác nhau.
3. Trang không có JSON-LD và không có trường nào hợp lệ (bị chặn, captcha, không phải tin) thì không tính.
4. Bộ nhớ giữ kết quả 100 trang gần nhất của mỗi domain. Trường nào lúc discover có trên ≥ 80% trang mẫu, mà giờ hợp lệ dưới 20% (cần ít nhất 30 trang), hoặc khớp JSON-LD dưới 20%, thì là **trường hỏng**. Trường hỏng được thêm vào `stale_fields`, crawler bỏ XPath đó và dùng JSON-LD cho trường đó.
5. Nếu hỏng một trường bắt buộc (title, description, location), hoặc hỏng hai trường bất kỳ, thì domain chuyển **`STALE`**. Coordinator discover lại (tối đa một lần mỗi ngày). Crawler không tạm dừng và vẫn dùng các XPath còn tốt. Discover lại thành công thì domain về `ACTIVE` với XPath mới. Thất bại thì giữ `STALE` và thử lại sau 24h.

Dòng nhập tay không có `field_coverage` (ITviec) thì chỉ theo dõi các trường bắt buộc. Các ngưỡng nằm trong `crawler.extractor.health`: `window`, `min-pages`, `healthy-baseline`, `broken-below`, `evaluate-every`, `rediscovery-delay`. Bật log `DEBUG` cho `org.example.jobpostingcrawler.extractor.health` để xem tỉ lệ từng trường.

Khi chạy discovery, các trang được tải theo cùng lịch giãn cách với worker (`DomainDelayService`). Trang bị HTTP 429 thì tải lại sau 15s, rồi 30s.

## Thao tác bằng SQL

```sql
-- Xem trạng thái
SELECT domain_key, status, attempts, error, next_retry_at FROM domain_extractor_config;

-- Xem kết quả discover của một domain
SELECT report FROM domain_extractor_config WHERE domain_key = 'topcv_vn';

-- Discover lại ngay (lần kiểm tra kế tiếp, hoặc khởi động lại app)
DELETE FROM domain_extractor_config WHERE domain_key = 'topcv_vn';

-- Domain bị STALE: trường nào hỏng, vì sao
SELECT stale_fields, stale_since, error, next_retry_at FROM domain_extractor_config WHERE status = 'STALE';

-- Discover lại một domain STALE ngay (lần kiểm tra kế tiếp)
UPDATE domain_extractor_config SET next_retry_at = now() WHERE domain_key = 'topcv_vn';

-- Tự viết hoặc sửa XPath
UPDATE domain_extractor_config
SET status = 'ACTIVE', xpaths = '{"title": "//h1", "salary": "//span[@class=''salary'']"}'::jsonb,
    stale_fields = NULL, stale_since = NULL, error = NULL, next_retry_at = NULL, updated_at = now()
WHERE domain_key = 'topcv_vn';
```

## Chạy discovery không cần cả app

`StructureDiscoveryCli` chạy discovery cho một site rồi in report và câu SQL để lưu kết quả. Lệnh này không cần DB, Redis hay RabbitMQ.

```bash
GEMINI_API_KEY=... java -cp "target/classes:<classpath>" \
  org.example.jobpostingcrawler.structurediscovery.StructureDiscoveryCli \
  --seed https://www.topcv.vn/tim-viec-lam-moi-nhat --prefix https://www.topcv.vn/viec-lam/ [--out result.sql]
```

## Test

```bash
./mvnw test
```

- Test discovery dùng LLM giả nên không cần mạng.
- `StructureDiscoveryLiveTest` chạy Playwright và Gemini thật trên TopCV, VietnamWorks, ITviec, và chỉ chạy khi có `GEMINI_API_KEY`.
- `UrlExtractorRelatedJobTest` gọi một trang job thật. Trang đó bị gỡ thì test được bỏ qua, cần thay URL.
- `JobPostCrawlerApplicationTests` khởi động cả app nên cần Postgres, Redis, RabbitMQ.
