package com.personalos.common.web;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.*;
class CoordinationLeaseBoundaryTest {
 @Test void leaseValidationFencingRenewalAndDeferredRelease(){
  var db=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  when(db.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class))).thenReturn(List.of(0));
  var coordination=new DatabaseCoordination(db,manager);
  try{
   for(int capacity:new int[]{0,101})assertThatThrownBy(()->coordination.acquire("r",capacity)).isInstanceOf(IllegalArgumentException.class);
   assertThatThrownBy(()->coordination.acquire("r".repeat(161),1)).isInstanceOf(IllegalArgumentException.class);
   var lease=coordination.acquire("r",1);assertThat(lease.valid()).isTrue();assertThatThrownBy(lease::fence).hasMessageContaining("transaction");
   TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
   when(db.queryForList(startsWith("SELECT slot"),any(Object[].class))).thenReturn(List.of(Map.of("slot",0)));lease.fence();
   when(db.queryForList(startsWith("SELECT slot"),any(Object[].class))).thenReturn(List.of());assertThatThrownBy(lease::fence).hasMessageContaining("lost");
   lease.close();lease.close();assertThat(lease.valid()).isFalse();assertThatThrownBy(lease::fence).hasMessageContaining("lost");
   var hooks=TransactionSynchronizationManager.getSynchronizations();TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);hooks.forEach(h->h.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
   verify(db,times(1)).update(startsWith("UPDATE runtime_lease SET owner=null"),any(Object[].class));
  }finally{TransactionSynchronizationManager.clear();coordination.close();}
 }
}
