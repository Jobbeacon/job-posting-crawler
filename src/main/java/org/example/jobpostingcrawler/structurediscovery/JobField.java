package org.example.jobpostingcrawler.structurediscovery;

/**
 * Fields of {@link org.example.jobpostingcrawler.model.ExtractorConfig} that the discovery module tries to
 * locate on a job posting page. {@link #jsonKey()} is the key in domain_extractor_config.xpaths.
 */
public enum JobField {
    TITLE("title", "title", Kind.TEXT, true,
            "The job title, usually the main heading (h1) of the posting."),
    COMPANY_NAME("companyName", "company-name", Kind.TEXT, false,
            "The name of the hiring company/employer of THIS posting."),
    COMPANY_LOGO_URL("companyLogoUrl", "company-logo-url", Kind.ATTRIBUTE, false,
            "The <img> element showing the hiring company's logo (the URL is read from an attribute, normally src)."),
    DESCRIPTION("description", "description", Kind.TEXT, true,
            "The smallest element containing the WHOLE job description body (responsibilities, requirements, "
                    + "benefits sections...). Pick the container, not a single paragraph."),
    EXPERIENCE_MONTHS("experienceMonths", "experience-months", Kind.TEXT, false,
            "The element holding the required years/months of experience value (e.g. '2 năm', 'Không yêu cầu')."),
    EMPLOYMENT_TYPE("employmentType", "employment-type", Kind.TEXT, false,
            "The element holding the working form / employment type value (e.g. 'Toàn thời gian', 'Bán thời gian', "
                    + "'Full-time', 'Part-time', 'Remote', 'Thực tập'). NOT the job level / rank ('Cấp bậc': "
                    + "'Nhân viên', 'Trưởng phòng', 'Senior', 'Manager'): null if the page shows only a level."),
    POSTED_DATE("postedDate", "posted-date", Kind.TEXT, false,
            "The element holding the date the job was posted/updated."),
    EXPIRED_DATE("expiredDate", "expired-date", Kind.TEXT, false,
            "The element holding the application deadline / expiry date (e.g. 'Hạn nộp hồ sơ: 30/12/2026')."),
    LOCATION("location", "location", Kind.TEXT, true,
            "The element holding the work location / address of this job."),
    SALARY("salary", "salary", Kind.TEXT, false,
            "The element holding the salary value (e.g. '15 - 20 triệu', 'Thỏa thuận', 'Up to 2000 USD')."),
    INDUSTRY("industry", "industry", Kind.LIST, false,
            "The CONTAINER element whose direct children are the job's industries / job domains / categories."),
    SKILLS("skills", "skills", Kind.LIST, false,
            "The CONTAINER element whose direct children are the skill tags/keywords of this job (technologies, tools, "
                    + "competencies: e.g. 'Java', 'Excel', 'Giao tiếp', 'Kubernetes'). NOT requirement summaries "
                    + "such as years of experience, education level, age or gender: null if the page has no skill tags."),
    BENEFITS("benefits", "benefits", Kind.TEXT, false,
            "The element holding the benefits / perks of the job (only if it is a separate block).");

    public enum Kind {
        /** Value is the text of the matched element(s). */
        TEXT,
        /** Value is an attribute of the matched element (xpath ends with /@attr). */
        ATTRIBUTE,
        /** Value is the list of texts of the matched container's direct children. */
        LIST
    }

    private final String jsonKey;
    private final String yamlKey;
    private final Kind kind;
    private final boolean required;
    private final String description;

    JobField(String jsonKey, String yamlKey, Kind kind, boolean required, String description) {
        this.jsonKey = jsonKey;
        this.yamlKey = yamlKey;
        this.kind = kind;
        this.required = required;
        this.description = description;
    }

    public String jsonKey() {
        return jsonKey;
    }

    public String yamlKey() {
        return yamlKey;
    }

    public Kind kind() {
        return kind;
    }

    /** Fields the crawler's validator requires (title, description, location). */
    public boolean required() {
        return required;
    }

    public String description() {
        return description;
    }
}
