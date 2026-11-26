# प्रगति जल — आफ्नै Android SIM SMS एप (APK)

यो फोल्डरमा तपाईंको **आफ्नै (Zero Third-Party / Termux नचाहिने)** **प्रगति जल Auto SIM Gateway** Android एपको पूरा प्रोजेक्ट छ।

यो एपले **दुवै मोड** एउटै एपबाट चलाउँछ:
1. **यही फोन Local Mode (`127.0.0.1:8765`):** Server URL खाली राख्नुहोस्! GitHub Pages र अफलाइन रिडिङमा Save थिच्नासाथ नसोधी सोझै SIM बाट नेपाली Unicode SMS जान्छ।
2. **PC-to-Phone Server Mode:** Server URL र Pairing Key राखे कम्प्युटरबाट Save गर्दा पनि यही फोनबाट SMS जान्छ।

## Android Studio बिना नै GitHub बाट सीधै `.apk` कसरी बनाउने?
1. यो प्रोजेक्ट (`.github/workflows/build-sms-apk.yml` र `android-sms-gateway/` फोल्डर सहित) तपाईंको **GitHub Repository** मा अपलोड/Push गर्नुहोस्।
2. GitHub को माथि **Actions** ट्याबमा जानुहोस् → **Build Pragati Jal SMS Gateway APK** मा क्लिक गरी **Run workflow** थिच्नुहोस्।
3. ~२ मिनेटमै **Artifacts → `PragatiJal-SMS-Gateway-APK`** भित्र **`app-debug.apk`** तयार हुन्छ — डाउनलोड गरी Android फोनमा इन्स्टल गर्नुहोस्!
4. एप खोलेर सफ्टवेयरको **Pairing Key** राखी **Start** थिच्नुहोस्, र मुख्य सफ्टवेयरको **SMS सेटिङ** मा **`⚡ यही फोनको Termux / APK १००% अटो SIM (127.0.0.1)`** छान्नुहोस्।
