package com.fortalsistemas.lio;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import cielo.orders.domain.CancellationRequest;
import cielo.orders.domain.CheckoutRequest;
import cielo.orders.domain.Credentials;
import cielo.orders.domain.Order;
import cielo.orders.domain.ResultOrders;
import cielo.sdk.info.InfoManager;
import cielo.sdk.order.OrderManager;
import cielo.sdk.order.PrinterListener;
import cielo.sdk.order.ServiceBindListener;
import cielo.sdk.order.cancellation.CancellationListener;
import cielo.sdk.order.payment.Payment;
import cielo.sdk.order.payment.PaymentCode;
import cielo.sdk.order.payment.PaymentError;
import cielo.sdk.order.payment.PaymentListener;
import cielo.sdk.printer.PrinterManager;

public class LioModule extends ReactContextBaseJavaModule {
    //CONSTANTS
    private String TAG = "RNLio";
    private static final int STATE_SERVICE_ACTIVE = 0;
    private static final int STATE_SERVICE_ERROR = 1;
    private static final int STATE_SERVICE_INACTIVE = 2;

    private String clientID = "";
    private String accessToken = "";
    private String ec = null;
    private boolean isSimulator = false;
    private Credentials credentials;
    private OrderManager orderManager;
    private Order order;
    private ReactApplicationContext reactContext;
    private String paymentType = ""; //DebitCard or CreditCard

    public enum Events {
        ON_CHANGE_SERVICE_STATE("onChangeServiceState"),
        ON_CHANGE_PAYMENT_STATE("onChangePaymentState"),
        ON_CHANGE_CANCELLATION_STATE("onChangeCancellationState"),
        ON_SERVICE_UNBOUND("onServiceUnbound"),
        ON_CHANGE_PRINTER_STATE("onChangePrinterState");

        private final String mName;

        Events(final String name) {
            mName = name;
        }

        @Override
        public String toString() {
            return mName;
        }
    }

    public LioModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "Lio";
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        return constants;
    }

    private PaymentListener createPaymentListener() {
        PaymentListener paymentListener = new PaymentListener() {
            @Override
            public void onStart() {
                Log.d(TAG, "O pagamento começou.");
                WritableMap stateService = Arguments.createMap();
                stateService.putInt("paymentState", 0);
                sendEvent(Events.ON_CHANGE_PAYMENT_STATE.toString(), stateService);
            }

            @Override
            public void onPayment(@NotNull Order order) {
                Log.d(TAG, "Um pagamento foi realizado");
                order.markAsPaid();
                orderManager.updateOrder(order);
                List<Payment> payments = order.getPayments();
                Iterator<Payment> paymentsIterator = payments.iterator();
                InfoManager infoManager = new InfoManager();

                double amount = 0.0;
                Integer installments = 1;
                String product = paymentType;
                String brand = "";
                String nsu = "";
                String authorizationCode = "";
                String authorizationDate = "";
                String logicNumber = "";


                Payment payment = order.getPayments().get(0);

                amount = order.getPaidAmount();
                installments = Math.toIntExact(payment.getInstallments());
                brand = payment.getBrand();
                nsu = payment.getCieloCode();
                authorizationCode = payment.getAuthCode();
                authorizationDate = payment.getRequestDate();
                logicNumber = infoManager.getSettings(reactContext).getLogicNumber();

                WritableMap stateService = Arguments.createMap();

                stateService.putInt("paymentState", 1);
                stateService.putInt("amount", (int) amount);
                stateService.putInt("installments", installments);
                stateService.putString("product", product);
                stateService.putString("brand", brand);
                stateService.putString("nsu", nsu);
                stateService.putString("authorizationCode", authorizationCode);
                stateService.putString("authorizationDate", authorizationDate);
                stateService.putString("logicNumber", logicNumber);

                sendEvent(Events.ON_CHANGE_PAYMENT_STATE.toString(), stateService);

                orderManager.unbind();
            }

            @Override
            public void onCancel() {
                Log.d(TAG, "A operação foi cancelada");
                WritableMap stateService = Arguments.createMap();
                stateService.putInt("paymentState", 2);
                sendEvent(Events.ON_CHANGE_PAYMENT_STATE.toString(), stateService);
            }

            @Override
            public void onError(@NotNull PaymentError paymentError) {
                Log.d(TAG, "Houve um erro no pagamento.");
                WritableMap stateService = Arguments.createMap();
                stateService.putInt("paymentState", 3);
                sendEvent(Events.ON_CHANGE_PAYMENT_STATE.toString(), stateService);
            }
        };

        return paymentListener;
    }

    private CancellationListener createCancellationListener() {
        CancellationListener cancellationListener = new CancellationListener() {
            @Override
            public void onSuccess(Order cancelledOrder) {
                Log.d(TAG, "O pagamento foi cancelado.");
                WritableMap cancellationState = Arguments.createMap();
                cancellationState.putInt("cancellationState", 1);
                sendEvent(Events.ON_CHANGE_CANCELLATION_STATE.toString(), cancellationState);
            }

            @Override
            public void onCancel() {
                Log.d(TAG, "O cancelamento foi abortado.");
                WritableMap cancellationState = Arguments.createMap();
                cancellationState.putInt("cancellationState", 2);
                sendEvent(Events.ON_CHANGE_CANCELLATION_STATE.toString(), cancellationState);
            }

            @Override
            public void onError(PaymentError paymentError) {
                Log.d(TAG,"Houve um erro no cancelamento");
                WritableMap cancellationState = Arguments.createMap();
                cancellationState.putInt("cancellationState", 3);
                sendEvent(Events.ON_CHANGE_CANCELLATION_STATE.toString(), cancellationState);
            }
        };

        return cancellationListener;
    }

    @ReactMethod
    public void setup(String clientID, String accessToken, String ec) {
        this.clientID = clientID;
        this.accessToken = accessToken;
        this.ec = ec;
        this.paymentType = "";
        this.order = null;
        this.orderManager = null;

        credentials = new Credentials(this.clientID, this.accessToken);
        orderManager = new OrderManager(credentials, this.reactContext);

        InfoManager infoManager = new InfoManager();
        String logicNumber = infoManager.getSettings(this.reactContext).getLogicNumber();

        if (logicNumber.equals("99999999-9")) {
            isSimulator = true;
        }

        ServiceBindListener serviceBindListener = new ServiceBindListener() {
            @Override
            public void onServiceBound() {
                Log.d(TAG, "Serviço CIELO conectado com Sucesso");
                WritableMap stateService = Arguments.createMap();
                stateService.putInt("stateService", STATE_SERVICE_ACTIVE);
                sendEvent(Events.ON_CHANGE_SERVICE_STATE.toString(), stateService);
            }

            @Override
            public void onServiceBoundError(Throwable throwable) {
                Log.d(TAG, "O serviço foi desvinculado");
                WritableMap stateService = Arguments.createMap();
                stateService.putInt("stateService", STATE_SERVICE_ERROR);
                sendEvent(Events.ON_CHANGE_SERVICE_STATE.toString(), stateService);
            }

            @Override
            public void onServiceUnbound() {
                Log.d(TAG, "O serviço foi desvinculado");
                WritableMap stateService = Arguments.createMap();
                stateService.putInt("stateService", STATE_SERVICE_INACTIVE);
                sendEvent(Events.ON_CHANGE_SERVICE_STATE.toString(), stateService);
            }
        };

        orderManager.bind(getCurrentActivity(), serviceBindListener);

    }

    @ReactMethod
    public void requestPaymentCredit(Integer amount, String orderId) {
        Log.d(TAG, "[requestPaymentCredit] VALOR:" + amount);
        order = orderManager.createDraftOrder(orderId);
        order.addItem("sku", "ORDER", amount, 1, "unidade");
        orderManager.placeOrder(order);
        paymentType = "CreditCard";

        CheckoutRequest checkoutRequest;

        if (isSimulator || this.ec == null) {
            checkoutRequest = new CheckoutRequest.Builder()
                    .orderId(order.getId())
                    .amount(amount)
                    .installments(1)
                    .paymentCode(PaymentCode.CREDITO_AVISTA)
                    .build();
        } else {
            checkoutRequest = new CheckoutRequest.Builder()
                    .orderId(order.getId())
                    .amount(amount)
                    .ec(this.ec)
                    .installments(1)
                    .paymentCode(PaymentCode.CREDITO_AVISTA)
                    .build();
        }


        this.orderManager.checkoutOrder(checkoutRequest, createPaymentListener());
    }

    @ReactMethod
    public void requestPaymentCreditInstallment(Integer amount, String orderId, Integer installments) {
        Log.d(TAG, "[requestPaymentCreditInstallment] VALOR:" + amount + " ORDERID:" + orderId + " inst:" + installments);
        order = orderManager.createDraftOrder(orderId);
        order.addItem("sku", "ORDER", amount, 1, "unidade");
        orderManager.placeOrder(order);
        paymentType = "CreditCard";

        CheckoutRequest checkoutRequest;

        if (isSimulator || this.ec == null) {
            checkoutRequest = new CheckoutRequest.Builder()
                    .orderId(order.getId())
                    .amount(amount)
                    .installments(installments)
                    .paymentCode(PaymentCode.CREDITO_PARCELADO_LOJA)
                    .build();
        } else {
            checkoutRequest = new CheckoutRequest.Builder()
                    .orderId(order.getId())
                    .amount(amount)
                    .ec(this.ec)
                    .installments(installments)
                    .paymentCode(PaymentCode.CREDITO_PARCELADO_LOJA)
                    .build();
        }

        this.orderManager.checkoutOrder(checkoutRequest, createPaymentListener());
    }

    @ReactMethod
    public void requestPaymentDebit(Integer amount, String orderId) {
        Log.d(TAG, "[requestPaymentDebit] VALOR:" + amount);
        order = orderManager.createDraftOrder(orderId);
        order.addItem("sku", "ORDER", amount, 1, "unidade");
        orderManager.placeOrder(order);
        paymentType = "DebitCard";

        CheckoutRequest checkoutRequest;

        if (isSimulator || this.ec == null) {
            checkoutRequest = new CheckoutRequest.Builder()
                    .orderId(order.getId())
                    .amount(amount)
                    .paymentCode(PaymentCode.DEBITO_AVISTA)
                    .build();
        } else {
            checkoutRequest = new CheckoutRequest.Builder()
                    .orderId(order.getId())
                    .amount(amount)
                    .ec(this.ec)
                    .paymentCode(PaymentCode.DEBITO_AVISTA)
                    .build();
        }

        this.orderManager.checkoutOrder(checkoutRequest, createPaymentListener());
    }

    @ReactMethod
    public void cancelPayment(String orderId, String authCode, String cieloCode, Integer amount) {
        CancellationRequest request;
        if (isSimulator || this.ec == null) {
            request = new CancellationRequest.Builder()
                    .orderId(orderId)
                    .authCode(authCode)
                    .cieloCode(cieloCode)
                    .value(amount)
                    .build();
        } else {
            request = new CancellationRequest.Builder()
                    .orderId(orderId)
                    .authCode(authCode)
                    .cieloCode(cieloCode)
                    .value(amount)
                    .ec(this.ec)
                    .build();
        }

        this.orderManager.cancelOrder(request, createCancellationListener());
    }

    @ReactMethod
    public void createDraftOrder(String orderId) {
        order = orderManager.createDraftOrder(orderId);
    }

    @ReactMethod
    public void addItems(String items) {
        try {
            JSONArray jsonItems = new JSONArray(items);
            for (int i = 0; i < jsonItems.length(); i++) {
                JSONObject jsonItem = jsonItems.getJSONObject(i);
                Log.d(TAG, jsonItem.getString("descricao"));
                order.addItem(jsonItem.getString("id_produto"), jsonItem.getString("descricao"), jsonItem.getLong("preco"), jsonItem.getInt("quantidade"), "unidade");
            }
            orderManager.placeOrder(order);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @ReactMethod
    public void placeOrder() {
        orderManager.placeOrder(order);
    }

    @ReactMethod
    public void checkoutOrder(int value, String paymentCode) {
        if (paymentCode.equals("debito")) {
            Log.d(TAG, paymentCode + " " + value);
            orderManager.checkoutOrder(order.getId(), 5000, PaymentCode.DEBITO_AVISTA, createPaymentListener());
        } else {
            if (paymentCode.equals("credito")) {
                orderManager.checkoutOrder(order.getId(), 5000, PaymentCode.DEBITO_AVISTA, createPaymentListener());
            }
        }

    }

    @ReactMethod
    public void getMachineInformation(Promise promise) {
        InfoManager infoManager = new InfoManager();
        String logicNumber = infoManager.getSettings(this.reactContext).getLogicNumber();
        String merchantCode = infoManager.getSettings(this.reactContext).getMerchantCode();

        WritableMap machineInformation = Arguments.createMap();
        machineInformation.putString("logicNumber", logicNumber);
        machineInformation.putString("merchantCode", merchantCode);

        promise.resolve(machineInformation);
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public WritableMap getOrderList(int pageSize, int page) {
        ResultOrders orders = this.orderManager.retrieveOrders(pageSize, page);
        WritableMap orderList = Arguments.createMap();

        if (orders == null) {
            return orderList;
        }

        List<Order> resultOrders = orders.getResults();

        WritableArray orderArray = Arguments.createArray();

        for (Order order : resultOrders) {
            WritableMap orderItem = Arguments.createMap();
            orderItem.putString("id", order.getId());
            orderItem.putDouble("paidAmount", order.getPaidAmount());
            orderItem.putString("createdAt", order.getCreatedAt().toString());

            WritableArray paymentsArray = Arguments.createArray();
            List<Payment> paymentsOfOrder = order.getPayments();

            for (Payment payment : paymentsOfOrder) {
                WritableMap paymentItem = Arguments.createMap();
                paymentItem.putString("orderId", order.getId());
                paymentItem.putString("id", payment.getId());
                paymentItem.putString("brand", payment.getBrand());
                paymentItem.putDouble("amount", payment.getAmount());
                paymentItem.putDouble("installments", payment.getInstallments());
                paymentItem.putString("authCode", payment.getAuthCode());
                paymentItem.putString("primaryCode", payment.getPrimaryCode());
                paymentItem.putString("statusCode", payment.getPaymentFields().get("statusCode"));
                paymentItem.putString("v40Code", payment.getPaymentFields().get("v40Code"));
                paymentItem.putString("primaryProductName", payment.getPaymentFields().get("primaryProductName"));
                paymentItem.putString("secondaryProductName", payment.getPaymentFields().get("secondaryProductName"));
                paymentItem.putString("nsu", payment.getCieloCode());
                paymentItem.putString("numberOfQuotas", payment.getPaymentFields().get("numberOfQuotas"));

                paymentsArray.pushMap(paymentItem);
            }
            orderItem.putArray("payments", paymentsArray);

            orderArray.pushMap(orderItem);
        }

        orderList.putArray("orderList", orderArray);
        return orderList;
    }

    private PrinterListener createPrinterListener() {
        PrinterListener printerListener = new PrinterListener() {
            @Override
            public void onPrintSuccess() {
                Log.d(TAG, "IMPRESSO COM SUCESSO");
                WritableMap printerState = Arguments.createMap();
                printerState.putInt("printerState", 0);
                sendEvent(Events.ON_CHANGE_PRINTER_STATE.toString(), printerState);
            }

            @Override
            public void onError(@Nullable Throwable throwable) {
                Log.d(TAG, "ERRO NA IMPRESSAO");
                WritableMap printerState = Arguments.createMap();
                printerState.putInt("printerState", 1);
                sendEvent(Events.ON_CHANGE_PRINTER_STATE.toString(), printerState);
            }

            @Override
            public void onWithoutPaper() {
                Log.d(TAG, "SEM PAPEL");
                WritableMap printerState = Arguments.createMap();
                printerState.putInt("printerState", 2);
                sendEvent(Events.ON_CHANGE_PRINTER_STATE.toString(), printerState);
            }
        };

        return printerListener;
    }

    private HashMap<String, Integer> getTextAlign(ReadableMap style) {
        HashMap<String, Integer> textStyle = new HashMap<>();

        for (Iterator<Map.Entry<String, Object>> it = style.getEntryIterator(); it.hasNext(); ) {
            Map.Entry<String, Object> entry = it.next();
            Double foo = (Double) entry.getValue();
            Integer bar = foo.intValue();
            textStyle.put(entry.getKey(), bar);
        }

        return textStyle;
    }

    @ReactMethod
    public void printText(String text, ReadableMap style) {
        Log.d(TAG, "PRINT TEXT: " + text);
        PrinterManager printerManager = new PrinterManager(this.reactContext);
        printerManager.printText(text, getTextAlign(style), createPrinterListener());
    }

    @ReactMethod
    public void printImage(String encodedImage, ReadableMap style) {
        Log.d(TAG, "PRINT IMAGE");
        PrinterManager printerManager = new PrinterManager(this.reactContext);

        byte[] decodedString = Base64.decode(encodedImage, Base64.DEFAULT);
        Bitmap bitmapImage = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
        printerManager.printImage(bitmapImage, getTextAlign(style), createPrinterListener());
    }

    @ReactMethod
    public void printQRCode(String text, int size, ReadableMap style){
        Log.d(TAG, "PRINT QRCODE: " + text);
        PrinterManager printerManager = new PrinterManager(this.reactContext);
        printerManager.printImage(QRCodeUtil.encodeAsBitmap(text,size,size),getTextAlign(style),createPrinterListener());
    }

    @ReactMethod
    public void printPhoto(String encodedImage, ReadableMap style) {
        Log.d(TAG, "PRINT IMAGE");
        PrinterManager printerManager = new PrinterManager(this.reactContext);

        String base64BW = resizeAndConvertToGrayscale(encodedImage, 240);
        byte[] decodedString = Base64.decode(base64BW, Base64.DEFAULT);
        Bitmap bitmapImage = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
        printerManager.printImage(bitmapImage, getTextAlign(style), createPrinterListener());
    }

    @ReactMethod
    public String resizeAndConvertToGrayscale(String base64Image, int newSize) {
         // Step 1: Decode the base64 string to Bitmap
        byte[] decodedByte = Base64.decode(base64Image, 0);
        Bitmap originalBitmap = BitmapFactory.decodeByteArray(decodedByte, 0, decodedByte.length);
        
        // Step 2: Resize the Bitmap to newSize x newSize
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, newSize, newSize, true);
        
        // Step 3: Convert the Bitmap to grayscale using a weighted luminance method
        int width = resizedBitmap.getWidth();
        int height = resizedBitmap.getHeight();
        Bitmap grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        
        for (int x = 0; x < width; ++x) {
            for (int y = 0; y < height; ++y) {
                int color = resizedBitmap.getPixel(x, y);
                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);
                
                // Calculate luminance of pixel
                int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                grayscaleBitmap.setPixel(x, y, Color.rgb(gray, gray, gray));
            }
        }
        
        // Step 4: Encode grayscale Bitmap back to base64 string
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        grayscaleBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    @ReactMethod
    public void unbind() {
        this.orderManager.unbind();
    }

    private void sendEvent(String eventName, @Nullable WritableMap params) {

        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);

    }
}
