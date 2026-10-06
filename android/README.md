# Android-app (skelet)

Kildefiler til at lægge ind i et nyt Android Studio-projekt (Kotlin, minSdk 26).
Endnu ikke bygget eller testet på en telefon.

- `PaymentParser.kt` – genkender MobilePay-notifikationer og læser beløbet. Ren Kotlin, unit-testet i `src/test`.
- `PaymentListener.kt` – `NotificationListenerService`, der fodrer parseren. `Dispenser` er en skitse, der mangler kø, database og USB-serial.

Manifest-udsnit til tjenesten:

```xml
<service
    android:name=".PaymentListener"
    android:exported="false"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

USB-serial til Arduinoen: `com.github.mik3y:usb-serial-for-android` (via JitPack).

Første skridt: sæt `Config.logMode = true`, modtag en testbetaling, og ret
`Config.parser` (pakkenavn + regex) efter det, der står i logcat.
