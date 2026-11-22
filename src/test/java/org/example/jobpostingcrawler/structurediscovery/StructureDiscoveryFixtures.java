package org.example.jobpostingcrawler.structurediscovery;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Synthetic job pages mimicking a typical Vietnamese job board layout. */
final class StructureDiscoveryFixtures {

    private StructureDiscoveryFixtures() {
    }

    static Document jobPage(String title, String company, String experience, String deadline, String salary,
                            String location, boolean withSalary, String... skills) {
        StringBuilder skillTags = new StringBuilder();
        for (String s : skills) {
            skillTags.append("<a class=\"tag\">").append(s).append("</a>");
        }
        StringBuilder menu = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            menu.append("<li class=\"menu-item\"><a href=\"/c/").append(i).append("\">Ngành ").append(i).append("</a></li>");
        }
        String html = """
                <html><head><title>%s - Tuyển dụng</title><meta property="og:title" content="%s">
                <script>var big = "%s";</script><style>.x{color:red}</style></head>
                <body>
                <header><nav class="main-nav"><ul>%s</ul></nav></header>
                <div class="search-filter"><div>Kinh nghiệm</div><div>Tất cả</div></div>
                <div class="modal fade" id="login-modal"><div class="modal-body">Đăng nhập để ứng tuyển</div></div>
                <main>
                  <div class="job-header">
                    <h1 class="job-title">%s</h1>
                    <div class="company"><img class="logo" src="https://cdn.example.com/%s.png" alt="%s"><a class="company-name" href="/cty">%s</a></div>
                    %s
                    <div class="info-grid">
                      <div class="info-item"><div class="info-label">Kinh nghiệm</div><div class="info-value">%s</div></div>
                      <div class="info-item"><div class="info-label">Hạn nộp hồ sơ</div><div class="info-value">%s</div></div>
                      <div class="info-item"><div class="info-label">Địa điểm</div><div class="info-value">%s</div></div>
                    </div>
                  </div>
                  <section class="job-description">
                    <h2>Mô tả công việc</h2><ul><li>Phát triển phần mềm</li><li>Review code</li></ul>
                    <h2>Yêu cầu</h2><p>Có kinh nghiệm làm việc với hệ thống lớn.</p>
                  </section>
                  <div class="skills-box"><h3>Kỹ năng:</h3><div class="skill-list">%s</div></div>
                </main>
                <div class="related-jobs">%s</div>
                <footer><p>© Job board</p></footer>
                </body></html>
                """.formatted(title, title, "x".repeat(5000), menu, title, company.hashCode(), company, company,
                withSalary ? "<span class=\"salary\">" + salary + "</span>" : "",
                experience, deadline, location, skillTags, relatedJobs());
        return Jsoup.parse(html, "https://jobs.example.com/viec-lam/" + title.hashCode() + ".html");
    }

    private static String relatedJobs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append("<div class=\"job-card\"><h3 class=\"job-title-small\"><a>Việc liên quan ").append(i)
                    .append("</a></h3><div class=\"company\"><span>Cty ").append(i)
                    .append("</span></div><div class=\"meta\"><span>HN</span><span>10 - 20 triệu</span><span>Hôm nay</span>")
                    .append("<span>Mới</span><span>Hot</span></div></div>");
        }
        return sb.toString();
    }
}
