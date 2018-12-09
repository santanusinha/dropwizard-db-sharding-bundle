TRUNCATE TABLE `orders`;
TRUNCATE TABLE `order_items`;
INSERT INTO `orders` (`id`, `order_ext_id`, `customer_id`, `amount`, `readOnly`) VALUES ('1', '11111111-2222-3333-4444-aaaaaaaaaaa1', 1, 10000, 1);
INSERT INTO `order_items` (`id`, `name`, `order_id`) VALUES ('0', 'test', '10');
INSERT INTO `order_items` (`id`, `name`, `order_id`) VALUES ('1', 'test', '10');