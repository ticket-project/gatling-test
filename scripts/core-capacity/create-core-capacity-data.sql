-- Core Admission Capacity 운영 테스트 전용 Oracle 데이터 생성 스크립트
--
-- 생성 범위
--   VENUES                       1행
--   SHOWS                        1행
--   SEATS                    2,000행
--   SHOW_GRADES                  4행
--   SHOW_SEATS               2,000행
--   PERFORMANCES                30행
--   PERFORMANCE_QUEUE_POLICIES  30행 (FORCE_OFF)
--   PERFORMANCE_SEATS       60,000행 (전부 AVAILABLE)
--
-- 중요
--   1. JetBrains Database Console에서는 전체 선택 후 일반 Execute(Ctrl+Enter)로 실행한다.
--      "Execute Selection as Single Statement"는 사용하지 않는다.
--      DBMS_OUTPUT 성공 메시지가 필요하면 Console의 Enable DBMS_OUTPUT을 켠다.
--      SQL Developer에서는 Run Script(F5), SQL*Plus/SQLcl에서는 @파일경로로 실행한다.
--   2. 고정 ID 영역 910000001부터를 사용한다. 실행 전 운영 DB 충돌 여부를 검토한다.
--   3. MEMBERS는 변경하지 않는다. 기존 ACTIVE MEMBER ID를 마지막 조회 결과로 제공한다.
--   4. 중간 검증이 하나라도 실패하면 전체 트랜잭션을 ROLLBACK한다.
--   5. JDBC Console 호환성을 위해 SET/WHENEVER 같은 SQL*Plus 전용 명령은 넣지 않는다.
--      아래 단일 PL/SQL 블록이 오류 시 직접 ROLLBACK하고 오류를 다시 발생시킨다.
--   6. 생성한 테이블을 COMMIT 전에 다시 읽어 검증하므로 INSERT SELECT는 직렬 DML로 실행한다.

DECLARE
    c_id_base              CONSTANT NUMBER := 910000000;
    c_venue_id             CONSTANT NUMBER := c_id_base + 1;
    c_show_id              CONSTANT NUMBER := c_id_base + 1;
    c_seat_count           CONSTANT PLS_INTEGER := 2000;
    c_performance_count    CONSTANT PLS_INTEGER := 30;
    c_created_by           CONSTANT VARCHAR2(255) := 'CORE_CAPACITY_20260812';

    v_count NUMBER;

    PROCEDURE fail_if_not_zero(p_count NUMBER, p_target VARCHAR2) IS
    BEGIN
        IF p_count <> 0 THEN
            RAISE_APPLICATION_ERROR(-20001, p_target || ' ID 영역이 이미 사용 중입니다. count=' || p_count);
        END IF;
    END;

    PROCEDURE assert_count(p_actual NUMBER, p_expected NUMBER, p_target VARCHAR2) IS
    BEGIN
        IF p_actual <> p_expected THEN
            RAISE_APPLICATION_ERROR(
                -20002,
                p_target || ' 생성 건수가 예상과 다릅니다. expected=' || p_expected || ', actual=' || p_actual
            );
        END IF;
    END;
BEGIN
    -- 고정 ID 영역을 사용하므로 기존 데이터와 충돌하면 INSERT 전에 즉시 중단한다.
    SELECT COUNT(*) INTO v_count FROM venues WHERE id = c_venue_id;
    fail_if_not_zero(v_count, 'VENUES');

    SELECT COUNT(*) INTO v_count FROM shows WHERE id = c_show_id;
    fail_if_not_zero(v_count, 'SHOWS');

    SELECT COUNT(*) INTO v_count
    FROM seats
    WHERE id BETWEEN c_id_base + 1 AND c_id_base + c_seat_count;
    fail_if_not_zero(v_count, 'SEATS');

    SELECT COUNT(*) INTO v_count
    FROM performances
    WHERE id BETWEEN c_id_base + 1 AND c_id_base + c_performance_count;
    fail_if_not_zero(v_count, 'PERFORMANCES');

    SELECT COUNT(*) INTO v_count
    FROM members
    WHERE deleted_at IS NULL
      AND role = 'MEMBER';
    IF v_count < c_seat_count THEN
        RAISE_APPLICATION_ERROR(
            -20003,
            'ACTIVE MEMBER가 2,000명보다 적습니다. actual=' || v_count
        );
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM shows
    WHERE title = '[LOAD TEST] Core Admission Capacity 2000석';
    fail_if_not_zero(v_count, 'SHOWS title');

    INSERT INTO venues (
        id, name, address, region, address_detail, zip_code,
        latitude, longitude, phone, image_url,
        view_box_width, view_box_height, seat_diameter, gap_x, gap_y,
        created_at, created_by
    ) VALUES (
        c_venue_id,
        '[LOAD TEST] Core Capacity Venue',
        '부하테스트 전용',
        'SEOUL',
        '운영 Core 용량 측정 전용 데이터',
        '00000',
        37.50000000,
        127.00000000,
        '02-0000-0000',
        NULL,
        500,
        356,
        4.8,
        2.5,
        2.5,
        LOCALTIMESTAMP,
        c_created_by
    );

    INSERT INTO shows (
        id, title, sub_title, info,
        start_date, end_date, view_count, sale_type,
        sale_start_date, sale_end_date, image,
        venue_id, running_minutes, performer_id,
        created_at, created_by
    ) VALUES (
        c_show_id,
        '[LOAD TEST] Core Admission Capacity 2000석',
        '실제 공연 규모를 모사한 운영 부하테스트 전용 공연',
        '실제 사용자에게 노출하거나 판매하지 않는 Core Admission Capacity 측정 전용 데이터',
        TRUNC(SYSDATE) + 60,
        TRUNC(SYSDATE) + 90,
        0,
        'GENERAL',
        LOCALTIMESTAMP - NUMTODSINTERVAL(1, 'DAY'),
        LOCALTIMESTAMP + NUMTODSINTERVAL(30, 'DAY'),
        NULL,
        c_venue_id,
        120,
        NULL,
        LOCALTIMESTAMP,
        c_created_by
    );

    -- 10개 구역 × 20개 행 × 10개 좌석 = 2,000석이다.
    -- 좌표는 500 × 356 viewBox 안의 50열 × 40행으로 배치한다.
    INSERT /*+ DISABLE_PARALLEL_DML */ INTO seats (
        id, section, row_no, seat_no, floor, x, y, created_at, created_by
    )
    SELECT
        c_id_base + LEVEL,
        'SEC-' || TO_CHAR(TRUNC((LEVEL - 1) / 200) + 1, 'FM00'),
        'ROW-' || TO_CHAR(TRUNC(MOD(LEVEL - 1, 200) / 10) + 1, 'FM00'),
        TO_CHAR(MOD(LEVEL - 1, 10) + 1, 'FM00'),
        CASE WHEN TRUNC((LEVEL - 1) / 200) + 1 <= 6 THEN 1 ELSE 2 END,
        20 + MOD(LEVEL - 1, 50) * 9,
        20 + TRUNC((LEVEL - 1) / 50) * 8,
        LOCALTIMESTAMP,
        c_created_by
    FROM dual
    CONNECT BY LEVEL <= c_seat_count;

    INSERT INTO show_grades (
        show_id, grade_code, grade_name, price, sort_order, created_at, created_by
    ) VALUES (c_show_id, 'VIP', 'VIP석', 150000, 1, LOCALTIMESTAMP, c_created_by);

    INSERT INTO show_grades (
        show_id, grade_code, grade_name, price, sort_order, created_at, created_by
    ) VALUES (c_show_id, 'R', 'R석', 120000, 2, LOCALTIMESTAMP, c_created_by);

    INSERT INTO show_grades (
        show_id, grade_code, grade_name, price, sort_order, created_at, created_by
    ) VALUES (c_show_id, 'S', 'S석', 90000, 3, LOCALTIMESTAMP, c_created_by);

    INSERT INTO show_grades (
        show_id, grade_code, grade_name, price, sort_order, created_at, created_by
    ) VALUES (c_show_id, 'A', 'A석', 60000, 4, LOCALTIMESTAMP, c_created_by);

    INSERT /*+ DISABLE_PARALLEL_DML */ INTO show_seats (
        show_id, seat_id, show_grade_id, created_at, created_by
    )
    SELECT
        c_show_id,
        s.id,
        sg.id,
        LOCALTIMESTAMP,
        c_created_by
    FROM seats s
    JOIN show_grades sg
      ON sg.show_id = c_show_id
     AND sg.grade_code = CASE
            WHEN TRUNC((s.id - c_id_base - 1) / 200) + 1 <= 2 THEN 'VIP'
            WHEN TRUNC((s.id - c_id_base - 1) / 200) + 1 <= 4 THEN 'R'
            WHEN TRUNC((s.id - c_id_base - 1) / 200) + 1 <= 7 THEN 'S'
            ELSE 'A'
         END
    WHERE s.id BETWEEN c_id_base + 1 AND c_id_base + c_seat_count;

    -- performance_no별 용도와 부하 단계는 같은 폴더의 README 표를 따른다.
    -- 모든 회차는 스크립트 실행 시점부터 30일간 주문 가능하다.
    INSERT /*+ DISABLE_PARALLEL_DML */ INTO performances (
        id, show_id, performance_no,
        start_time, end_time,
        order_open_time, order_close_time,
        max_can_hold_count, hold_time,
        created_at, created_by
    )
    SELECT
        c_id_base + LEVEL,
        c_show_id,
        LEVEL,
        LOCALTIMESTAMP + NUMTODSINTERVAL(60 + LEVEL, 'DAY'),
        LOCALTIMESTAMP + NUMTODSINTERVAL(60 + LEVEL, 'DAY') + NUMTODSINTERVAL(120, 'MINUTE'),
        LOCALTIMESTAMP - NUMTODSINTERVAL(1, 'DAY'),
        LOCALTIMESTAMP + NUMTODSINTERVAL(30, 'DAY'),
        2,
        600,
        LOCALTIMESTAMP,
        c_created_by
    FROM dual
    CONNECT BY LEVEL <= c_performance_count;

    -- Admission Token 기능을 바꾸지 않고 기존 정책으로 Queue 비대상 회차를 만든다.
    INSERT /*+ DISABLE_PARALLEL_DML */ INTO performance_queue_policies (
        performance_id, queue_mode, queue_level,
        preopen_queue_start_at, waiting_room_message, reason,
        created_at, created_by
    )
    SELECT
        p.id,
        'FORCE_OFF',
        'LEVEL_1',
        NULL,
        'Core Admission Capacity 직접 측정 전용',
        'Queue 없이 Core 안전 입장률을 측정하는 전용 회차',
        LOCALTIMESTAMP,
        c_created_by
    FROM performances p
    WHERE p.id BETWEEN c_id_base + 1 AND c_id_base + c_performance_count;

    INSERT /*+ DISABLE_PARALLEL_DML */ INTO performance_seats (
        performance_id, seat_id, state, price, created_at, created_by
    )
    SELECT
        p.id,
        ss.seat_id,
        'AVAILABLE',
        sg.price,
        LOCALTIMESTAMP,
        c_created_by
    FROM performances p
    JOIN show_seats ss
      ON ss.show_id = c_show_id
    JOIN show_grades sg
      ON sg.id = ss.show_grade_id
    WHERE p.id BETWEEN c_id_base + 1 AND c_id_base + c_performance_count;

    -- COMMIT 전에 전체 생성 결과를 검증한다.
    SELECT COUNT(*) INTO v_count
    FROM seats
    WHERE id BETWEEN c_id_base + 1 AND c_id_base + c_seat_count;
    assert_count(v_count, c_seat_count, 'SEATS');

    SELECT COUNT(*) INTO v_count FROM show_grades WHERE show_id = c_show_id;
    assert_count(v_count, 4, 'SHOW_GRADES');

    SELECT COUNT(*) INTO v_count FROM show_seats WHERE show_id = c_show_id;
    assert_count(v_count, c_seat_count, 'SHOW_SEATS');

    SELECT COUNT(*) INTO v_count
    FROM performances
    WHERE id BETWEEN c_id_base + 1 AND c_id_base + c_performance_count;
    assert_count(v_count, c_performance_count, 'PERFORMANCES');

    SELECT COUNT(*) INTO v_count
    FROM performance_queue_policies
    WHERE performance_id BETWEEN c_id_base + 1 AND c_id_base + c_performance_count
      AND queue_mode = 'FORCE_OFF';
    assert_count(v_count, c_performance_count, 'PERFORMANCE_QUEUE_POLICIES');

    SELECT COUNT(*) INTO v_count
    FROM performance_seats
    WHERE performance_id BETWEEN c_id_base + 1 AND c_id_base + c_performance_count
      AND state = 'AVAILABLE'
      AND price IS NOT NULL;
    assert_count(v_count, c_performance_count * c_seat_count, 'PERFORMANCE_SEATS');

    SELECT COUNT(*) INTO v_count
    FROM (
        SELECT performance_id
        FROM performance_seats
        WHERE performance_id BETWEEN c_id_base + 1 AND c_id_base + c_performance_count
        GROUP BY performance_id
        HAVING COUNT(*) <> c_seat_count
    );
    assert_count(v_count, 0, 'PERFORMANCE_SEATS per performance');

    COMMIT;

    DBMS_OUTPUT.PUT_LINE('Core capacity test data created.');
    DBMS_OUTPUT.PUT_LINE('showId=' || c_show_id);
    DBMS_OUTPUT.PUT_LINE(
        'performanceId=' || (c_id_base + 1) || '..' || (c_id_base + c_performance_count)
    );
    DBMS_OUTPUT.PUT_LINE('seatId=' || (c_id_base + 1) || '..' || (c_id_base + c_seat_count));
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END;
/

-- 아래 결과의 MEMBER_ID 열만 scripts/core-capacity/member-ids.txt로 내보낸다.
-- ID는 연속일 필요가 없으며 Console은 실제 순서 그대로 JWT subject로 사용한다.
SELECT id AS member_id
FROM (
    SELECT id
    FROM members
    WHERE deleted_at IS NULL
      AND role = 'MEMBER'
    ORDER BY id
)
WHERE ROWNUM <= 10000;
