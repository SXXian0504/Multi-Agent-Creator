-- 初始化数据库脚本
-- 使用说明：请手动执行 sql/create_table.sql 文件中的所有SQL语句
-- 或者使用以下命令（如果已配置MySQL命令行）：
-- mysql -uroot -p123456 < sql/create_table.sql
-- mysql版本8.0
-- 临时配置：
SET NAMES utf8mb4;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;
-- insert不成功，设置gbk
SET NAMES gbk;