package sample;

import org.springframework.transaction.annotation.Transactional;

public class PaymentService implements Payment {
    @Transactional
    public void pay(String orderId) {
        audit(orderId);
    }

    public void pay(String orderId, int amount) {
        pay(orderId);
    }

    private void audit(String orderId) {
        System.out.println(orderId);
    }
}
