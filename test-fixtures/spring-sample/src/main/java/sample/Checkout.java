package sample;

public class Checkout {
    private Payment payment;

    public void checkout(String orderId) {
        payment.pay(orderId);
    }
}
