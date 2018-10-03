CREATE TABLE `order` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `order_ext_id` varchar(255) DEFAULT NULL,
  `customer_id` varchar(255) DEFAULT NULL,
  `amount` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

CREATE TABLE `order_item` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `order_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

CREATE TABLE `customer_bucket` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `customer_id` varchar(255) DEFAULT NULL,
  `bucket_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

CREATE TABLE `bucket_shard` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `bucket_id` varchar(255) DEFAULT NULL,
  `shard_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uidx_bucketidshardid_bucketshardmapping` (`bucket_id`,`shard_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

INSERT INTO `customer_bucket` (`customer_id`, `bucket_id`) VALUES ("1", "1");
INSERT INTO `customer_bucket` (`customer_id`, `bucket_id`) VALUES ("2", "2");
INSERT INTO `customer_bucket` (`customer_id`, `bucket_id`) VALUES ("3", "3");

INSERT INTO `bucket_shard` (`bucket_id`, `shard_id`) VALUES ("1", "shard1");
INSERT INTO `bucket_shard` (`bucket_id`, `shard_id`) VALUES ("2", "shard1");
INSERT INTO `bucket_shard` (`bucket_id`, `shard_id`) VALUES ("3", "shard2");