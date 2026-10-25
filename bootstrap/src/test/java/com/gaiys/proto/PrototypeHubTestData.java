package com.gaiys.proto;

import java.time.Duration;
import org.springframework.r2dbc.core.DatabaseClient;

/** 隔离同一测试类共享数据库中的原型业务数据，保留启动生成的部署身份及系统账号。 */
final class PrototypeHubTestData {
    private PrototypeHubTestData() {}

    static void clear(DatabaseClient database) {
        database.sql(
                        "truncate table p_review_event, p_review_message, p_review_thread, p_review_session, p_review_challenge, p_review_rate, p_review_guest, p_review_invitation, p_review_unverified_guest,"
                                + " p_preview_session, p_project_member, p_creation_receipt,"
                                + " p_share, p_file_cleanup, p_revision, p_version, p_project")
                .fetch()
                .rowsUpdated()
                .block(Duration.ofSeconds(15));
    }
}
