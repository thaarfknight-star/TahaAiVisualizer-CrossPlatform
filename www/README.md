# TahaAi Visualizer — LG webOS TV Edition

این نسخه از ویژولایزر Python/PySide6 به یک Web App مستقل برای LG webOS TV تبدیل شده است.

## امکانات
- 23 حالت بصری مشابه نسخه اصلی
- Demo mode بدون نیاز به ورودی صدا
- کنترل مناسب Magic Remote
- Preset های Neon / Minimal / Pulse / Ocean / Sunset / Monochrome
- رنگ، پس‌زمینه، Mirror، Sensitivity، Smoothing، Detail، Glow، Opacity
- 30/60/120 FPS
- ذخیره تنظیمات در حافظه TV
- پخش Audio URL مستقیم و واکنش ویژولایزر به آن در صورت پشتیبانی CORS
- Full-screen طراحی‌شده برای 16:9

## محدودیت مهم
System Audio Loopback نسخه Python روی LG webOS قابل انتقال مستقیم نیست؛ Web App تلویزیون به خروجی صدای کل سیستم دسترسی خام ندارد. برای همین این نسخه Demo و Network Audio را ارائه می‌کند. Spotify و سرویس‌های DRM نیز از داخل این اپ مستقیماً قابل capture نیستند.

## ساخت IPK
LG webOS TV SDK / CLI را نصب کنید، سپس در همین پوشه اجرا کنید:

    ares-package .

خروجی یک فایل `.ipk` است.

برای نصب روی TV در Developer Mode طبق راهنمای رسمی webOS TV از `ares-install` و `ares-launch` استفاده کنید.

## ساختار
- appinfo.json
- index.html
- icon.png

ID برنامه: com.tahaai.visualizer


## Mobile optimization
The WebApp includes responsive layouts for Android/iOS phones and small tablets, touch-friendly controls, safe-area support, and landscape-phone handling.
