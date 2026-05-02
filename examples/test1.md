```mermaid
erDiagram
    admin {
        bigint id PK
        varchar(255) name "이름"
        varchar(255) email
        varchar(1000) password
        varchar(255) role_type
        datetime created_at
        datetime updated_at
    }

    banner_file {
        bigint id PK
        bigint file_id
        tinyint deleted
        datetime deleted_at
        bigint deleted_by
        datetime created_at
        varchar(255) created_by
        datetime updated_at
    }

    bidding {
        bigint id PK
        tinyint automated "자동견적여부"
        varchar(255) status "입찰 상태"
        bigint partner_id
        bigint order_id "입찰 ID"
        varchar(255) cost_type
        bigint amount
        text message
        tinyint deleted
        datetime created_at
        datetime updated_at
    }

    bidding_file {
        bigint id PK
        bigint bidding_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    bidding_price {
        bigint id PK
        bigint bidding_price
        varchar(20) automated_bidding_price
        datetime created_at
        datetime updated_at
    }

    bidding_push_history {
        bigint id PK
        bigint bidding_id
        bigint user_id
        tinyint read_yn "읽음 여부"
        datetime created_at
        datetime updated_at
    }

    chat {
        bigint id PK
        bigint chat_room_id
        bigint chat_file_id
        varchar(255) type "채팅 타입"
        varchar(2000) message
        varchar(255) messenger_type
        bigint messenger_id
        datetime created_at
        datetime updated_at
    }

    chat_file {
        bigint id PK
        bigint chat_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    chat_room {
        bigint id PK
        bigint order_id
        bigint bidding_id
        tinyint user_bookmark "유저 즐겨찾기 여부"
        tinyint partner_bookmark "파트너 즐겨찾기 여부"
        tinyint user_joined "유저 채팅방 입장 여부"
        datetime last_chatted_at "마지막 채팅 시간"
        tinyint closed
        datetime closed_at
        datetime created_at
        datetime updated_at
    }

    files {
        bigint id PK
        varchar(255) external_id "External ID"
        varchar(255) content_type
        tinyint private
        varchar(255) file_type
        varchar(255) region
        varchar(255) bucket
        varchar(255) upload_path
        varchar(255) original_file_name
        varchar(255) file_name
        tinyint owned "주인존재여부"
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    frequent_bidding {
        bigint id PK
        bigint partner_id
        varchar(1000) title
        varchar(255) cost_type
        bigint amount
        text description
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    frequent_bidding_file {
        bigint id PK
        bigint frequent_bidding_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    news {
        bigint id PK
        varchar(255) title
        text content
        tinyint deleted "삭제 여부"
        datetime deleted_at "삭제 일시"
        datetime created_at
        bigint created_by
        datetime updated_at
        bigint updated_by
    }

    news_file {
        bigint id PK
        bigint news_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    notice {
        bigint id PK
        varchar(255) title
        text content
        tinyint deleted "삭제 여부"
        datetime deleted_at "삭제 일시"
        datetime created_at
        bigint created_by
        datetime updated_at
        bigint updated_by
    }

    notice_file {
        bigint id PK
        bigint notice_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    order_push_history {
        bigint id PK
        bigint order_id
        bigint partner_id
        tinyint read_yn "읽음 여부"
        datetime created_at
        datetime updated_at
    }

    orders {
        bigint id PK
        bigint user_id "유저 ID"
        varchar(255) status "입찰 상태 （입찰대기, 예약중, 예약취소, 완료）"
        varchar(255) service_type "방역 유형（해충, 소독, 드론）"
        varchar(255) space_type "방역 공간（주거, 상업, 사무, 교육, 기타）"
        varchar(255) disinfection_locations "방역 위치（실내, 외부）"
        varchar(255) additional_service_types "추가 방역 유형（（해충, 소독, 드론, 기타）"
        varchar(255) additional_service_message "추가 방역 요청사항 （기타인 경우만 존재, 나머지 null）"
        varchar(255) space_area "방역 공간 면적"
        varchar(255) disinfection_period "방역 주기（1회, 정기, 방역 후 결정）"
        varchar(255) disinfection_expect_date_type "방역 희망일 유형"
        date disinfection_expect_date "방역 희망일（유형이 SPECIFIC인 경우만 존재）"
        varchar(255) si_do
        varchar(255) si_gun_gu
        varchar(2000) comment "문의사항"
        varchar(255) cancel_reason "취소사유"
        datetime canceled_at "취소일자"
        datetime created_at
        datetime updated_at
    }

    partner_alarm {
        bigint partner_id PK
        tinyint order_message_app_push "주문,메시지 앱 푸시 수신 여부"
        tinyint auto_bidding_app_push "자동견적 앱푸시 수신여부"
        tinyint review_profile_tip_app_push "리뷰 프로필 팁 앱푸시 수신여부"
        tinyint notice_app_push "방플 소식 앱푸시 수신여부"
        tinyint do_not_disturb "방해금지모드 여부"
        datetime created_at
        datetime updated_at
    }

    partner_automated_bidding {
        bigint id PK
        bigint partner_id "파트너 ID"
        varchar(255) status "자동 견적 활성화 상태"
        varchar(255) service_types "서비스 유형"
        varchar(255) space_types "공간 유형"
        varchar(255) cost_type "가격 유형（시간당/총비용）"
        bigint amount "가격"
        text description "설명"
        bigint daily_budget "일일 예산"
        bigint today_paid_budget "오늘 사용된 예산"
        bigint today_bidding_count "오늘 자동 입찰한 갯수"
        tinyint deleted
        datetime created_at
        datetime updated_at
        datetime deleted_at
    }

    partner_automated_bidding_file {
        bigint id PK
        bigint partner_automated_bidding_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    partner_automated_bidding_regions {
        bigint partner_automated_bidding_id
        varchar(255) si_do "이동가능한 시도"
        varchar(255) si_gun_gu "이동가능한 시군구"
    }

    partner_details {
        bigint partner_id PK
        bigint profile_file_id "프로필사진 파일ID"
        tinyint self_authenticated
        varchar(255) payment_types
        time callable_from
        time callable_to
        bigint experienced
        bigint number_of_employees
        tinyint can_tax_invoice
        varchar(2000) short_introduce_message
        text introduce_message
        bigint review_count "리뷰 갯수"
        datetime latest_bid_at "최근 입찰 시점"
        decimal(5_2) score "리뷰 평점"
        bigint completed_bidding_count "거래종료된 입찰 갯수"
        datetime created_at
        datetime updated_at
        tinyint admin_used
    }

    partner_file {
        bigint id PK
        bigint partner_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    partner_issue_history {
        bigint id PK
        bigint partner_id
        varchar(255) status
        varchar(1000) reason
        datetime created_at
        datetime updated_at
        bigint created_by
    }

    partner_link {
        bigint id PK
        bigint partner_id
        varchar(255) site_type
        text url
        datetime created_at
        datetime updated_at
    }

    partner_movable_regions {
        bigint partner_id
        varchar(255) si_do "이동가능한 시도"
        varchar(255) si_gun_gu "이동가능한 시군구"
    }

%% 파트너 결제 내역
    partner_payment_history {
        bigint id PK "ID"
        bigint partner_id "파트너 ID"
        varchar(255) payment_id "결제 Unique ID"
        varchar(255) pay_gate "PG 사"
        varchar(255) pay_gate_key "PG 사 고유 결제 키"
        bigint amount
        varchar(255) error_message "에러 메시지"
        varchar(255) status "결제 상태 - READY: 결제 예정, CONFIRM: 결제 완료, CANCEL: 취소"
        datetime paid_at "결제일시"
        datetime created_at "생성일자"
        datetime updated_at "수정일자"
        datetime canceled_at "취소 일시"
        varchar(255) cancel_reason "취소 사유"
    }

    partner_qna {
        bigint id PK
        bigint partner_id
        varchar(1000) question
        text answer
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    partner_report {
        bigint id PK
        varchar(255) status
        bigint order_id
        bigint bidding_id
        bigint partner_id "신고한 파트너 ID"
        bigint user_id "신고 당한 파트너 ID"
        varchar(255) reason
        datetime created_at
        datetime updated_at
    }

%%  파트너 거래 내역 
    partner_transaction {
        bigint id PK "ID"
        bigint request_bidding_id " Bidding ID "
        bigint partner_id " 파트너 ID "
        varchar(255) transaction_type " 트랜잭션 유형（EARN/ USE） "
        bigint amount
        bigint ref_partner_transaction_id " 참조 ID "
        datetime(6) created_at " 생성일자 "
        datetime(6) updated_at " 수정일자 "
    }

%%  파트너 거래 상세 내역 
    partner_transaction_detail {
        bigint id PK " ID "
        bigint partner_id " 파트너 ID "
        bigint partner_transaction_id " 파트너 트랜잭션 ID "
        bigint request_bidding_id "캐시사용 입찰 ID"
        varchar(255) cash_type " 캐시 유형（NORMAL/BONUS） "
        varchar(255) transaction_type " 트랜잭션 유형（EARN/ USE） "
        bigint amount
        varchar(8) earn_tag " 유효기간（6자리）+캐시유형（2자리） "
        datetime(6) expired_at " 만료일자 "
        datetime created_at " 생성일자 "
        datetime updated_at " 수정일자 "
    }

    partners {
        bigint id PK
        varchar(255) status "파트너 상태 （ISSUE_READY/ISSUE_COMPLETED）"
        datetime approved_at "심사완료 일시"
        varchar(255) name
        varchar(255) email
        varchar(255) gender
        varchar(255) password
        varchar(255) phone_number
        varchar(255) business_registration_number "사업자 등록번호"
        varchar(255) disinfection_certification_number "소독자격증 번호"
        varchar(255) disinfection_education_certification_number "방역교육이수증 번호"
        varchar(255) drone_license_number "드론자격증 번호"
        varchar(255) land_lot_based_address
        varchar(255) road_name_based_address
        varchar(255) post_code
        varchar(255) service_types "서비스유형"
        datetime created_at
        datetime updated_at
        varchar(255) deleted_reason "탈퇴사유"
        datetime deleted_at "탈퇴 시간"
    }

    public_offices {
        bigint id PK
        varchar(255) si_do
        varchar(255) si_gun_gu
        varchar(255) name
        bigint post_code
        varchar(255) address
        geometry point
        datetime created_at
        datetime updated_at
    }

    review {
        bigint id PK
        bigint user_id
        bigint bidding_id
        decimal(3_2) rate
        text content
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    review_file {
        bigint id PK
        bigint review_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }

    user_alarm {
        bigint user_id PK
        tinyint bidding_app_push "견적서 도착, 메시지 앱푸시 수신여부"
        tinyint notice_app_push "방플 소식 앱푸시 수신여부"
        tinyint do_not_disturb "방해금지모드 여부"
        datetime created_at
        datetime updated_at
    }

    user_report {
        bigint id PK
        varchar(255) status
        bigint order_id
        bigint bidding_id
        bigint partner_id "신고 당한 파트너 ID"
        bigint user_id "신고한 유저 ID"
        varchar(255) reason
        varchar(1000) reason_detail "신고 이유 상세"
        datetime created_at
        datetime updated_at
    }

    users {
        bigint id PK
        varchar(255) naver_id
        bigint kakao_id
        bigint profile_file_id "프로필 파일 ID"
        varchar(255) name
        varchar(255) email
        varchar(255) password
        varchar(255) phone_number
        datetime created_at
        datetime updated_at
        varchar(255) deleted_reason "탈퇴사유"
        datetime deleted_at "탈퇴 시간"
    }

%% FK: banner_file.file_id -> files.id
    files ||--o{ banner_file : ""
%% FK: bidding.order_id -> orders.id
    orders ||--o{ bidding : ""
%% FK: bidding.partner_id -> partners.id
    partners ||--o{ bidding : ""
%% FK: bidding_file.bidding_id -> bidding.id
    bidding ||--o{ bidding_file : ""
%% FK: bidding_file.file_id -> files.id
    files ||--o{ bidding_file : ""
%% FK: bidding_push_history.bidding_id -> bidding.id
    bidding ||--o{ bidding_push_history : ""
%% FK: bidding_push_history.user_id -> users.id
    users ||--o{ bidding_push_history : ""
%% FK: chat.chat_file_id -> chat_file.id
    chat_file ||--o{ chat : ""
%% FK: chat.chat_room_id -> chat_room.id
    chat_room ||--o{ chat : ""
%% FK: chat_file.chat_id -> chat.id
    chat ||--o{ chat_file : ""
%% FK: chat_file.file_id -> files.id
    files ||--o{ chat_file : ""
%% FK: chat_room.bidding_id -> bidding.id
    bidding ||--o{ chat_room : ""
%% FK: chat_room.order_id -> orders.id
    orders ||--o{ chat_room : ""
%% FK: frequent_bidding.partner_id -> partners.id
    partners ||--o{ frequent_bidding : ""
%% FK: frequent_bidding_file.file_id -> files.id
    files ||--o{ frequent_bidding_file : ""
%% FK: frequent_bidding_file.frequent_bidding_id -> frequent_bidding.id
    frequent_bidding ||--o{ frequent_bidding_file : ""
%% FK: news_file.file_id -> files.id
    files ||--o{ news_file : ""
%% FK: news_file.news_id -> news.id
    news ||--o{ news_file : ""
%% FK: notice_file.file_id -> files.id
    files ||--o{ notice_file : ""
%% FK: notice_file.notice_id -> notice.id
    notice ||--o{ notice_file : ""
%% FK: order_push_history.order_id -> orders.id
    orders ||--o{ order_push_history : ""
%% FK: order_push_history.partner_id -> partners.id
    partners ||--o{ order_push_history : ""
%% FK: orders.user_id -> users.id
    users ||--o{ orders : ""
%% FK: partner_alarm.partner_id -> partners.id
    partners ||--o{ partner_alarm : ""
%% FK: partner_automated_bidding.partner_id -> partners.id
    partners ||--o{ partner_automated_bidding : ""
%% FK: partner_automated_bidding_file.file_id -> files.id
    files ||--o{ partner_automated_bidding_file : ""
%% FK: partner_automated_bidding_file.partner_automated_bidding_id -> partner_automated_bidding.id
    partner_automated_bidding ||--o{ partner_automated_bidding_file : ""
%% FK: partner_automated_bidding_regions.partner_automated_bidding_id -> partner_automated_bidding.id
    partner_automated_bidding ||--o{ partner_automated_bidding_regions : ""
%% FK: partner_details.partner_id -> partners.id
    partners ||--o{ partner_details : ""
%% FK: partner_file.file_id -> files.id
    files ||--o{ partner_file : ""
%% FK: partner_file.partner_id -> partners.id
    partners ||--o{ partner_file : ""
%% FK: partner_issue_history.partner_id -> partners.id
    partners ||--o{ partner_issue_history : ""
%% FK: partner_link.partner_id -> partners.id
    partners ||--o{ partner_link : ""
%% FK: partner_movable_regions.partner_id -> partners.id
    partners ||--o{ partner_movable_regions : ""
%% FK: partner_payment_history.partner_id -> partners.id
    partners ||--o{ partner_payment_history : ""
%% FK: partner_qna.partner_id -> partners.id
    partners ||--o{ partner_qna : ""
%% FK: partner_report.bidding_id -> bidding.id
    bidding ||--o{ partner_report : ""
%% FK: partner_report.order_id -> orders.id
    orders ||--o{ partner_report : ""
%% FK: partner_report.partner_id -> partners.id
    partners ||--o{ partner_report : ""
%% FK: partner_report.user_id -> users.id
    users ||--o{ partner_report : ""
%% FK: partner_transaction.partner_id -> partners.id
    partners ||--o{ partner_transaction : ""
%% FK: partner_transaction_detail.partner_transaction_id -> partner_transaction.id
    partner_transaction ||--o{ partner_transaction_detail : ""
%% FK: partner_transaction_detail.partner_id -> partners.id
    partners ||--o{ partner_transaction_detail : ""
%% FK: review.bidding_id -> bidding.id
    bidding ||--o{ review : ""
%% FK: review.user_id -> users.id
    users ||--o{ review : ""
%% FK: review_file.file_id -> files.id
    files ||--o{ review_file : ""
%% FK: review_file.review_id -> review.id
    review ||--o{ review_file : ""
%% FK: user_alarm.user_id -> users.id
    users ||--o{ user_alarm : ""
%% FK: user_report.bidding_id -> bidding.id
    bidding ||--o{ user_report : ""
%% FK: user_report.order_id -> orders.id
    orders ||--o{ user_report : ""
%% FK: user_report.partner_id -> partners.id
    partners ||--o{ user_report : ""
%% FK: user_report.user_id -> users.id
    users ||--o{ user_report : ""

```

