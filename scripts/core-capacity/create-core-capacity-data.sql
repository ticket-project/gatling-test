-- Core Admission Capacity 운영 테스트 전용 Oracle 데이터 생성 스크립트
--
-- 생성 범위
--   VENUES                       1행
--   SHOWS                        1행
--   SEATS                    2,000행
--   SHOW_GRADES                  4행
--   SHOW_SEATS               2,000행
--   MEMBERS                  2,000행 (JWT subject와 일치하는 ACTIVE 전용 회원)
--   PERFORMANCES                30행
--   PERFORMANCE_QUEUE_POLICIES  30행 (FORCE_OFF)
--   PERFORMANCE_SEATS       60,000행 (전부 AVAILABLE)
--
-- 중요
--   1. SQL*Plus, SQLcl 또는 SQL Developer의 Run Script로 실행한다.
--   2. 고정 ID 영역 910000001부터를 사용한다. 실행 전 운영 DB 충돌 여부를 검토한다.
--   3. 전용 회원은 이 SQL에서 최초 1회 생성하며, 주문과 Redis 데이터는 건드리지 않는다.
--   4. 중간 검증이 하나라도 실패하면 전체 트랜잭션을 ROLLBACK한다.

SET SERVEROUTPUT ON
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

DECLARE
    c_id_base              CONSTANT NUMBER := 910000000;
    c_venue_id             CONSTANT NUMBER := c_id_base + 1;
    c_show_id              CONSTANT NUMBER := c_id_base + 1;
    c_seat_count           CONSTANT PLS_INTEGER := 2000;
    c_member_id_base       CONSTANT NUMBER := 0;
    c_member_count         CONSTANT PLS_INTEGER := c_seat_count;
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
    WHERE id BETWEEN c_member_id_base + 1 AND c_member_id_base + c_member_count;
    fail_if_not_zero(v_count, 'MEMBERS');

    SELECT COUNT(*) INTO v_count
    FROM members
    WHERE email LIKE 'core-capacity-%@loadtest.invalid';
    fail_if_not_zero(v_count, 'MEMBERS email');

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
    INSERT INTO seats (
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

    -- Console이 같은 memberId 범위로 운영 JWT와 booking feeder를 자동 생성한다.
    -- 비밀번호가 없는 소셜 회원 형태이며, deleted_at이 NULL이므로 주문 검증상 ACTIVE 회원이다.
    INSERT INTO members (
        id, email, password, name, role, deleted_at,
        created_at, created_by
    )
    SELECT
        c_member_id_base + LEVEL,
        'core-capacity-' || TO_CHAR(LEVEL, 'FM0000') || '@loadtest.invalid',
        NULL,
        '[LOAD TEST] Core Capacity ' || TO_CHAR(LEVEL, 'FM0000'),
        'MEMBER',
        NULL,
        LOCALTIMESTAMP,
        c_created_by
    FROM dual
    CONNECT BY LEVEL <= c_member_count;

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

    INSERT INTO show_seats (
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
    INSERT INTO performances (
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
    INSERT INTO performance_queue_policies (
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

    INSERT INTO performance_seats (
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
    FROM members
    WHERE id BETWEEN c_member_id_base + 1 AND c_member_id_base + c_member_count
      AND deleted_at IS NULL
      AND role = 'MEMBER';
    assert_count(v_count, c_member_count, 'MEMBERS');

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
    DBMS_OUTPUT.PUT_LINE(
        'memberId=' || (c_member_id_base + 1) || '..' || (c_member_id_base + c_member_count)
    );
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END;
/
