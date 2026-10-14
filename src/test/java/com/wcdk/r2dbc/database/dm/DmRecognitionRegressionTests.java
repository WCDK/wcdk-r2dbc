package com.wcdk.r2dbc.database.dm;
import org.junit.jupiter.api.Test;
import io.r2dbc.spi.ConnectionFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DmRecognitionRegressionTests {
    @Test void springProvidersAndCoreDialectShareVersionedDmRecognition(){
        for(String name:new String[]{"DM Database 60","  DaMeng 8 ","DM"}){
            var factory=mock(ConnectionFactory.class);when(factory.getMetadata()).thenReturn(()->name);
            assertThat(DmR2dbcSupport.isDm(factory)).isTrue();
            assertThat(new DmSpringDialectProvider().getDialect(factory)).isPresent();
            assertThat(new DmBindMarkersFactoryProvider().getBindMarkers(factory)).isNotNull();
        }
        var factory=mock(ConnectionFactory.class);when(factory.getMetadata()).thenReturn(()->"PostgreSQL");
        assertThat(DmR2dbcSupport.isDm(factory)).isFalse();
    }
}
