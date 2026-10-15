-- Course Phase 1: additive schema and one-time legacy snapshots.
-- Baseline: 20261014.0. Existing course IDs, content, stops, images and relationships stay intact.
-- Before application startup: back up the DB and pause course/destination/category writers.
-- MySQL DDL commits independently; a failure can leave partial schema. Do not blindly rerun or edit
-- an applied version. Inspect the failure and use the project's Flyway recovery process.
-- Missing/blank translations or unresolvable countries remain NULL, never invented placeholders.
-- Until Phase 2, legacy application writes do not maintain these new snapshot tables.

ALTER TABLE courses
    ADD COLUMN course_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'TRAVELER',
    ADD COLUMN status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLISHED',
    ADD COLUMN summary VARCHAR(300) NULL,
    ADD COLUMN cover_image_url VARCHAR(255) NULL,
    ADD COLUMN structured_content JSON NULL,
    ADD CONSTRAINT chk_courses_kind CHECK (course_kind IN ('OFFICIAL', 'TRAVELER')),
    ADD CONSTRAINT chk_courses_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN'));

-- All IDs match the signed BIGINT PKs in the existing schema. Timestamps match courses' precision.
-- Constraints are installed before backfill so invalid rows fail instead of being silently skipped.
CREATE TABLE course_days (
    id BIGINT NOT NULL AUTO_INCREMENT,
    course_id BIGINT NOT NULL,
    day_number INT NOT NULL,
    title VARCHAR(100) NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_course_days_number (course_id, day_number),
    CONSTRAINT fk_course_days_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE,
    CONSTRAINT chk_course_days_number CHECK (day_number >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE course_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    course_day_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    item_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLACE',
    destination_id BIGINT NULL,
    country_id BIGINT NULL,
    place_name VARCHAR(255) NULL,
    address VARCHAR(500) NULL,
    latitude DECIMAL(10,8) NULL,
    longitude DECIMAL(11,8) NULL,
    start_time TIME NULL,
    end_time TIME NULL,
    memo VARCHAR(1000) NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_course_items_order (course_day_id, display_order),
    KEY idx_course_items_destination (destination_id),
    KEY idx_course_items_country (country_id),
    CONSTRAINT fk_course_items_day FOREIGN KEY (course_day_id) REFERENCES course_days (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_items_destination FOREIGN KEY (destination_id) REFERENCES destinations (id) ON DELETE SET NULL,
    CONSTRAINT fk_course_items_country FOREIGN KEY (country_id) REFERENCES country_categories (id) ON DELETE SET NULL,
    CONSTRAINT chk_course_items_order CHECK (display_order >= 1),
    CONSTRAINT chk_course_items_type CHECK (item_type IN ('PLACE', 'STAY', 'FOOD', 'TRANSPORT', 'ETC')),
    -- No CHECK references destination_id/country_id: SET NULL actions must remain legal in MySQL.
    CONSTRAINT chk_course_items_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE course_themes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_order INT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_course_themes_code (code),
    CONSTRAINT chk_course_themes_order CHECK (display_order >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE course_theme_mappings (
    course_id BIGINT NOT NULL,
    theme_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    PRIMARY KEY (course_id, theme_id),
    UNIQUE KEY uq_course_theme_mappings_order (course_id, display_order),
    KEY idx_course_theme_mappings_theme (theme_id),
    CONSTRAINT fk_course_theme_mappings_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_theme_mappings_theme FOREIGN KEY (theme_id) REFERENCES course_themes (id) ON DELETE RESTRICT,
    CONSTRAINT chk_course_theme_mappings_order CHECK (display_order BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE course_countries (
    course_id BIGINT NOT NULL,
    country_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    PRIMARY KEY (course_id, country_id),
    UNIQUE KEY uq_course_countries_order (course_id, display_order),
    KEY idx_course_countries_country (country_id),
    CONSTRAINT fk_course_countries_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_countries_country FOREIGN KEY (country_id) REFERENCES country_categories (id) ON DELETE RESTRICT,
    CONSTRAINT chk_course_countries_order CHECK (display_order >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO course_themes (code, name, display_order) VALUES
    ('ESSENTIAL', '필수 코스', 1),
    ('HISTORY_CULTURE', '역사·문화', 2),
    ('NATURE_SCENERY', '자연·풍경', 3),
    ('FOOD_CAFE', '맛집·카페', 4),
    ('HEALING', '휴양·힐링', 5),
    ('ACTIVITY', '액티비티·체험', 6),
    ('SHOPPING', '쇼핑', 7),
    ('PHOTO_SPOT', '사진·감성', 8),
    ('THEME_PARK', '테마파크·즐길거리', 9),
    ('LOCAL', '로컬여행', 10);

-- Includes soft-deleted courses and courses with no destinations. New timestamps describe the
-- snapshot creation, not the legacy updated_at (which can change just because of page views).
INSERT INTO course_days (course_id, day_number)
SELECT id, 1 FROM courses;

INSERT INTO course_items (
    course_day_id, display_order, item_type, destination_id, country_id,
    place_name, address, latitude, longitude
)
WITH RECURSIVE
legacy_regions AS (
    SELECT DISTINCT d.region_id
    FROM course_destinations cd
    JOIN destinations d ON d.id = cd.destination_id
),
ancestry AS (
    SELECT region.id AS source_region_id, region.id AS region_id, region.parent_id,
           SUBSTRING_INDEX(region.code, '-', 1) AS country_code
    FROM country_categories region
    JOIN legacy_regions legacy ON legacy.region_id = region.id

    -- The recursive state is stable for each visited ancestor. DISTINCT makes cycles terminate.
    UNION DISTINCT

    SELECT ancestry.source_region_id, parent.id, parent.parent_id, ancestry.country_code
    FROM ancestry
    JOIN country_categories parent ON parent.id = ancestry.parent_id
),
resolved_countries AS (
    SELECT ancestry.source_region_id, MIN(region.id) AS country_id
    FROM ancestry
    JOIN country_categories region ON region.id = ancestry.region_id
    LEFT JOIN country_categories parent ON parent.id = region.parent_id
    -- Same code/ancestry meaning as CourseMapper.findDestinationCountries, with the country
    -- shape from CountryCategoryMapper.selectCourseCountries. Continents/regions aren't countries.
    WHERE region.code = ancestry.country_code
      AND region.code NOT LIKE '%-%'
      AND (
          (region.parent_id IS NULL AND EXISTS (
              SELECT 1 FROM country_categories child
              WHERE child.parent_id = region.id AND child.code LIKE CONCAT(region.code, '-%')
          ))
          OR (region.parent_id IS NOT NULL AND parent.id IS NOT NULL AND parent.parent_id IS NULL)
      )
    GROUP BY ancestry.source_region_id
    -- More than one plausible country is ambiguous: keep the snapshot country NULL.
    HAVING COUNT(DISTINCT region.id) = 1
),
ranked_names AS (
    SELECT dt.destination_id, dt.name,
           ROW_NUMBER() OVER (
               PARTITION BY dt.destination_id
               ORDER BY CASE dt.language_code
                   WHEN 'ko' THEN 1 WHEN 'en' THEN 2 WHEN 'ja' THEN 3
                   WHEN 'zh-CN' THEN 4 WHEN 'zh-TW' THEN 5 ELSE 6
               END, BINARY dt.language_code, dt.id
           ) AS name_rank
    FROM destination_translations dt
    WHERE NULLIF(TRIM(dt.name), '') IS NOT NULL
      AND EXISTS (SELECT 1 FROM course_destinations linked WHERE linked.destination_id = dt.destination_id)
)
SELECT day.id,
       ROW_NUMBER() OVER (PARTITION BY cd.course_id ORDER BY cd.visit_order, cd.id),
       CASE d.type
           WHEN 'ACCOMMODATION' THEN 'STAY'
           WHEN 'RESTAURANTS' THEN 'FOOD'
           WHEN 'CAFE' THEN 'FOOD'
           ELSE 'PLACE'
       END,
       cd.destination_id, country.country_id, snapshot.name,
       NULL AS address,
       CASE WHEN d.latitude IS NOT NULL AND d.longitude IS NOT NULL
                 AND d.latitude BETWEEN -90 AND 90 AND d.longitude BETWEEN -180 AND 180
            THEN d.latitude ELSE NULL END,
       CASE WHEN d.latitude IS NOT NULL AND d.longitude IS NOT NULL
                 AND d.latitude BETWEEN -90 AND 90 AND d.longitude BETWEEN -180 AND 180
            THEN d.longitude ELSE NULL END
FROM course_destinations cd
-- LEFT JOIN keeps every legacy stop in the INSERT. An orphan course/destination is an explicit
-- NOT NULL/FK failure rather than a silently lost row (normally prevented by existing FKs).
LEFT JOIN course_days day ON day.course_id = cd.course_id AND day.day_number = 1
LEFT JOIN destinations d ON d.id = cd.destination_id
LEFT JOIN resolved_countries country ON country.source_region_id = d.region_id
LEFT JOIN ranked_names snapshot ON snapshot.destination_id = cd.destination_id AND snapshot.name_rank = 1
ORDER BY cd.course_id, cd.visit_order, cd.id;

-- A non-NULL legacy country wins, including on soft-deleted courses. Do not rewrite it using stops.
INSERT INTO course_countries (course_id, country_id, display_order)
SELECT id, country_id, 1 FROM courses WHERE country_id IS NOT NULL;

-- For legacy NULL countries only: keep distinct snapshot countries in first-visit order.
INSERT INTO course_countries (course_id, country_id, display_order)
SELECT first_country.course_id, first_country.country_id,
       ROW_NUMBER() OVER (
           PARTITION BY first_country.course_id ORDER BY first_country.first_order, first_country.country_id
       )
FROM (
    SELECT day.course_id, item.country_id, MIN(item.display_order) AS first_order
    FROM course_days day
    JOIN course_items item ON item.course_day_id = day.id
    JOIN courses c ON c.id = day.course_id
    WHERE day.day_number = 1 AND c.country_id IS NULL AND item.country_id IS NOT NULL
    GROUP BY day.course_id, item.country_id
) first_country
ORDER BY first_country.course_id, first_country.first_order, first_country.country_id;

-- Transition compatibility only. Leave known countries and all other legacy data untouched.
-- Explicit self-assignment suppresses TIMESTAMP auto-update and preserves NULL timestamps too.
UPDATE courses c
JOIN course_countries primary_country ON primary_country.course_id = c.id AND primary_country.display_order = 1
SET c.country_id = primary_country.country_id,
    c.updated_at = c.updated_at
WHERE c.country_id IS NULL;

-- Existing courses deliberately have zero theme mappings; no theme is inferred from legacy text.
