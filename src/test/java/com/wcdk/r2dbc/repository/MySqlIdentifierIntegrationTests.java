package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.R2dbcRepositoryOperations;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.query.QueryWrapper;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/*** 默认SQL模式下真实MySQL保留字列的仓储CRUD回归，专属表结束清理。 @author wcdk ***/
@EnabledIfSystemProperty(named = "wcdk.r2dbc.mysql.integration", matches = "true")
class MySqlIdentifierIntegrationTests {
    @Test
    void repositoryCrudAndDerivedQueriesUseMySqlIdentifierQuotes() {
        var options = ConnectionFactoryOptions.parse(System.getProperty("wcdk.r2dbc.mysql.url", "r2dbc:mysql://localhost:3306/wcdk_shop")).mutate()
                .option(ConnectionFactoryOptions.USER, System.getenv().getOrDefault("WCDK_DB_USER", "root"))
                .option(ConnectionFactoryOptions.PASSWORD, System.getenv().getOrDefault("WCDK_DB_PASSWORD", "root")).build();
        var client = DatabaseClient.create(ConnectionFactories.get(options));
        var properties = new WcdkR2dbcProperties();
        properties.setSqlLogEnabled(false);
        var util = new R2dbcUtil(client, null, null, properties);
        var registry = new RepositoryXmlRegistry(new org.springframework.core.io.support.PathMatchingResourcePatternResolver(), properties);
        var repository = (SettingRepository) new RepositoryProxyFactory(new R2dbcRepositoryOperations(util), properties, registry)
                .create(SettingRepository.class);
        var setting = new Setting();
        setting.id = UUID.randomUUID().toString().replace("-", "");
        setting.settingKey = "before";
        // 固定专属测试表使用CREATE而非IF NOT EXISTS；已有同名表时拒绝，避免清理他人数据。
        StepVerifier.create(client.sql("CREATE TABLE `wcdk_identifier_qa` (`id` varchar(32) PRIMARY KEY, `key` varchar(64))").fetch().rowsUpdated())
                .expectNextCount(1).verifyComplete();
        try {
            StepVerifier.create(client.sql("SELECT @@sql_mode AS mode").map((row, metadata) -> row.get("mode", String.class)).one())
                    .assertNext(mode -> assertThat(mode).doesNotContain("ANSI_QUOTES")).verifyComplete();
            StepVerifier.create(repository.insert(setting)).expectNext(setting).verifyComplete();
            StepVerifier.create(repository.selectById(setting.id)).assertNext(row -> assertThat(row.settingKey).isEqualTo("before")).verifyComplete();
            StepVerifier.create(repository.selectList(new QueryWrapper<Setting>().eq("key", "before").orderByAsc("key").limit(1)))
                    .assertNext(row -> assertThat(row.id).isEqualTo(setting.id)).verifyComplete();
            StepVerifier.create(repository.findBySettingKey("before"))
                    .assertNext(row -> assertThat(row.id).isEqualTo(setting.id)).verifyComplete();
            setting.settingKey = "after";
            StepVerifier.create(repository.updateById(setting)).expectNext(1L).verifyComplete();
            StepVerifier.create(repository.selectCount(new QueryWrapper<Setting>().eq("key", "after"))).expectNext(1L).verifyComplete();
            StepVerifier.create(repository.deleteById(setting.id)).expectNext(1L).verifyComplete();
            StepVerifier.create(repository.findAll()).verifyComplete();
        } finally {
            StepVerifier.create(client.sql("DROP TABLE `wcdk_identifier_qa`").fetch().rowsUpdated())
                    .expectNextCount(1).expectComplete().verify(Duration.ofSeconds(15));
        }
    }

    interface SettingRepository extends BaseRepository<Setting> {
        reactor.core.publisher.Mono<Setting> findBySettingKey(String key);
    }

    @Table("wcdk_identifier_qa")
    public static class Setting {
        @Id public String id;
        @Column("key") public String settingKey;
    }
}
