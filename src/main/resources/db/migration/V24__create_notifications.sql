-- In-app notifications (header bell, /notifications): order updates, approvals, reviews. Newest first per account;
-- read_at stays NULL until the account opens it or marks everything read.
CREATE TABLE notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    title VARCHAR(120) NOT NULL,
    body VARCHAR(400) NOT NULL,
    link VARCHAR(200) NOT NULL,
    read_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL
);

-- the list, newest first (also serves fk_notifications_user)
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at);
-- the bell's unread count
CREATE INDEX idx_notifications_user_read ON notifications (user_id, read_at);

ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
