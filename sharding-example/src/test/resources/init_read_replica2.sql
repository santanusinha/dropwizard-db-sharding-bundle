DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `order_ext_id` varchar(255) DEFAULT NULL,
  `customer_id` varchar(255) DEFAULT NULL,
  `amount` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `order_items`;
CREATE TABLE `order_items` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `order_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`)
);
TRUNCATE TABLE `orders`;
TRUNCATE TABLE `order_items`;
INSERT INTO `orders` (`id`, `order_ext_id`, `customer_id`, `amount`) VALUES ('2', '11111111-2222-3333-4444-aaaaaaaaaaa2', 3, 10000);
INSERT INTO `order_items` (`id`, `name`, `order_id`) VALUES ('0', 'test', '10');
INSERT INTO `order_items` (`id`, `name`, `order_id`) VALUES ('1', 'test', '10');
shutdown defrag;