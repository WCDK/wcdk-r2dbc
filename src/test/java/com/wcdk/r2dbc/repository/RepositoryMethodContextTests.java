package com.wcdk.r2dbc.repository;
import com.wcdk.r2dbc.*;
import com.wcdk.r2dbc.config.*;
import com.wcdk.r2dbc.execution.lifecycle.*;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.query.Param;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RepositoryMethodContextTests {
 @Test void inheritedAndRedeclaredMethodsKeepAnnotations() throws Exception {
  var a=new Fixture<>(Inherited.class); a.repository.deleteById(9L).block(); check(a.contexts.getFirst(), Parent.class,"parentId",9L);
  var b=new Fixture<>(Redeclared.class); b.repository.deleteById(8L).block(); check(b.contexts.getFirst(), Redeclared.class,"childId",8L);
 }
 @Test void overloadsKeepTheInvokedSignature() throws Exception {
  var f=new Fixture<>(Overloaded.class); Object id=7L; f.repository.deleteById(id).block();
  check(f.contexts.getFirst(),Overloaded.class,"crudId",id);
  assertThat(f.repository.deleteById("local").block()).isEqualTo(11L); assertThat(f.contexts).hasSize(1);
 }
 @Test void defaultBridgeMethodsReenterTheProxy() throws Exception {
  var f=new Fixture<>(BridgeRepository.class); GenericRemove<Long> generic=f.repository;
  assertThat(generic.remove(6L).block()).isEqualTo(7L);
  assertThat(Arrays.stream(BridgeRepository.class.getDeclaredMethods()).anyMatch(m->m.isBridge()&&m.isDefault())).isTrue();
  check(f.contexts.getFirst(),Parent.class,"parentId",6L);
 }
 @Test void genericAndZeroArgumentMethodsKeepOriginalArguments() throws Exception {
  var f=new Fixture<>(Inherited.class); User user=new User(); assertThat(f.repository.insert(user).block()).isSameAs(user);
  assertThat(f.contexts.getFirst().getMethod()).isEqualTo(BaseRepository.class.getMethod("insert",Object.class));
  assertThat(f.contexts.getFirst().getArguments()).containsExactly(user);
  f.repository.findAll().collectList().block();
  assertThat(f.contexts.getLast().getMethod()).isEqualTo(BaseRepository.class.getMethod("findAll"));
  assertThat(f.contexts.getLast().getArguments()).isEmpty();
 }
 @Test void concreteGenericOverridesKeepTheirAnnotationThroughTheCompilerBridge() throws Exception {
  var f = new Fixture<>(Typed.class);
  User user = new User();
  BaseRepository<User> base = f.repository;
  assertThat(base.insert(user).block()).isSameAs(user);
  var method = f.contexts.getFirst().getMethod();
  assertThat(method).isEqualTo(Typed.class.getMethod("insert", User.class));
  assertThat(method.getParameters()[0].getAnnotation(Param.class).value()).isEqualTo("typedEntity");
  assertThat(method.isBridge()).isFalse();
  assertThat(Arrays.stream(Typed.class.getDeclaredMethods()).anyMatch(java.lang.reflect.Method::isBridge)).isTrue();
 }
 private void check(SqlExecutionContext c,Class<?> owner,String annotation,Object value) throws Exception {
  assertThat(c.getMethod()).isEqualTo(owner.getMethod("deleteById",Object.class));
  assertThat(c.getMethod().getDeclaringClass()).isEqualTo(owner);
  assertThat(c.getMethod().getParameters()[0].getAnnotation(Param.class).value()).isEqualTo(annotation);
  assertThat(c.getArguments()).containsExactly(value);
 }
 static class Fixture<R> {
  final List<SqlExecutionContext> contexts=new ArrayList<>(); final R repository;
  Fixture(Class<R> type) throws Exception {
   var factory=mock(ConnectionFactory.class); when(factory.getMetadata()).thenReturn(()->"PostgreSQL");
   var properties=new WcdkR2dbcProperties(); var resolver=mock(ResourcePatternResolver.class);
   when(resolver.getResources(anyString())).thenReturn(new Resource[0]);
   var registry=new RepositoryXmlRegistry(resolver,properties);
   SqlLifecycleInterceptor interceptor=new SqlLifecycleInterceptor(){
    @Override public void beforeCompile(SqlExecutionContext c){
     contexts.add(c); c.cacheHit(switch(c.getMethod().getName()){
      case "insert"->c.getArguments()[0]; case "findAll"->List.of(); default->7L; });
    }
   };
   var util=new R2dbcUtil(DatabaseClient.create(factory),null,null,properties,new WcdkSpringR2dbcProperties(),null,
    new SqlLifecycleInterceptorChain(List.of(interceptor),List.of()));
   repository=type.cast(new RepositoryProxyFactory(new R2dbcRepositoryOperations(util),properties,registry).create(type));
  }
 }
 interface Parent extends BaseRepository<User>{ @Override Mono<Long> deleteById(@Param("parentId") Object id); }
 interface Inherited extends Parent{}
 interface Typed extends Parent {
  @Override Mono<User> insert(@Param("typedEntity") User user);
 }
 interface Redeclared extends Parent{ @Override Mono<Long> deleteById(@Param("childId") Object id); }
 interface Overloaded extends BaseRepository<User>{
  @Override Mono<Long> deleteById(@Param("crudId") Object id);
  default Mono<Long> deleteById(@Param("localId") String id){return Mono.just(11L);}
 }
 interface GenericRemove<T>{Mono<Long> remove(T value);}
 interface BridgeRepository extends Parent,GenericRemove<Long>{ @Override default Mono<Long> remove(Long value){return deleteById(value);} }
 static class User{ @Id Long id=1L; String name="测试"; }
}
