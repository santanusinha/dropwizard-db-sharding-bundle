DROP TABLE IF EXISTS `customer_bucket`;
CREATE TABLE `customer_bucket` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `customer_id` varchar(255) DEFAULT NULL,
  `bucket_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `bucket_shard`;
CREATE TABLE `bucket_shard` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `bucket_id` varchar(255) DEFAULT NULL,
  `shard_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uidx_bucketidshardid_bucketshardmapping` (`bucket_id`,`shard_id`)
);

INSERT INTO `customer_bucket` (`customer_id`, `bucket_id`) VALUES ('1', '1');
INSERT INTO `customer_bucket` (`customer_id`, `bucket_id`) VALUES ('2', '2');
INSERT INTO `customer_bucket` (`customer_id`, `bucket_id`) VALUES ('3', '3');

INSERT INTO `bucket_shard` (`bucket_id`, `shard_id`) VALUES ('1', 'shard1');
INSERT INTO `bucket_shard` (`bucket_id`, `shard_id`) VALUES ('2', 'shard1');
INSERT INTO `bucket_shard` (`bucket_id`, `shard_id`) VALUES ('3', 'shard2');