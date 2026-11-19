package dev.codeatlas.web;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LocalDirectoryControllerTest {
 @Test void headlessDesktopReturnsRepeatableManualFallbackWithoutTakingTheWindowLock(){
  var picker=new LocalDirectoryController(()->true);
  for(int i=0;i<2;i++) assertThat(((java.util.Map<?,?>)picker.select()).get("status")).isEqualTo("unavailable");
 }
}
