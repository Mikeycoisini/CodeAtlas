CREATE TABLE learning_pack_adoptions (
 generation_id VARCHAR(36) PRIMARY KEY,
 payload MEDIUMTEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(generation_id) REFERENCES ai_generations(id)
);
