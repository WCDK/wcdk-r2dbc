package com.wcdk.r2dbc.execution;

import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.binding.BindMarkersFactoryResolver;
import reactor.util.context.ContextView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*** 为异构路由选择参数转换器及占位符，同时保留路由工厂的事务连接身份。 @author wcdk ***/
final class RoutingParameterBinder extends ParameterBinder {
    private final DynamicRoutingConnectionFactory routing;
    private final Map<ConnectionFactory, ParameterBinder> binders = new ConcurrentHashMap<>();
    private final Map<ConnectionFactory, DatabaseClient> clients = new ConcurrentHashMap<>();

    RoutingParameterBinder(DynamicRoutingConnectionFactory routing) {
        this.routing = routing;
    }

    /*** 无执行上下文的旧接口沿用主数据源，保持默认路由语义。 @author wcdk ***/
    @Override
    public DatabaseClient.GenericExecuteSpec bind(DatabaseClient client, String sql, Map<?, ?> parameters) {
        return bind(client, sql, parameters, reactor.util.context.Context.empty());
    }

    /*** 在订阅上下文中选择目标数据库的参数类型及绑定标记。 @author wcdk ***/
    @Override
    public DatabaseClient.GenericExecuteSpec bind(DatabaseClient client, String sql, Map<?, ?> parameters,
                                                  ContextView context) {
        ConnectionFactory target = routing.getConnectionFactory(R2dbcDataSourceContext.get(context));
        ParameterBinder binder = binders.computeIfAbsent(target, ParameterBinder::forConnectionFactory);
        DatabaseClient targetClient = clients.computeIfAbsent(target, factory -> DatabaseClient.builder()
                // 必须沿用路由工厂，Spring 才能复用已绑定到该工厂的事务连接。
                .connectionFactory(routing)
                .bindMarkers(BindMarkersFactoryResolver.resolve(factory))
                .build());
        return binder.bind(targetClient, sql, parameters);
    }
}
