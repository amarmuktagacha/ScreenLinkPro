<div align="center">

# 📱 ScreenLink Pro

### লোকাল নেটওয়ার্কে দুই ফোনের মধ্যে রিয়েল-টাইম স্ক্রিন শেয়ারিং — কোনো ক্লাউড, কোনো অ্যাকাউন্ট ছাড়াই।

<p>
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/min%20SDK-24-orange?style=for-the-badge" />
</p>

<p>
  <img src="https://img.shields.io/github/actions/workflow/status/amarmuktagacha/ScreenLinkPro/android-build.yml?branch=main&label=Build&style=flat-square" />
  <img src="https://img.shields.io/github/v/release/amarmuktagacha/ScreenLinkPro?label=Release&style=flat-square&color=success" />
  <img src="https://img.shields.io/github/downloads/amarmuktagacha/ScreenLinkPro/total?label=Downloads&style=flat-square&color=blue" />
  <img src="https://img.shields.io/github/license/amarmuktagacha/ScreenLinkPro?style=flat-square" />
</p>

<p>
  <a href="https://github.com/amarmuktagacha/ScreenLinkPro/releases/latest">
    <img src="https://img.shields.io/badge/⬇️_Download-Latest%20APK-brightgreen?style=for-the-badge" />
  </a>
</p>

</div>

---

## ✨ পরিচিতি

**ScreenLink Pro** একটি Android 14-কম্প্যাটিবল লোকাল-নেটওয়ার্ক স্ক্রিন-শেয়ারিং অ্যাপ। একটি ফোন **Host** হিসেবে কাজ করে তার ডিসপ্লে স্ট্রিম করে; দ্বিতীয় ফোনটি **Viewer** হিসেবে সেটা লাইভ দেখে। কোনো ক্লাউড অ্যাকাউন্ট বা বাইরের সার্ভারের প্রয়োজন নেই — সব ডেটা লোকাল Wi-Fi/হটস্পটের মধ্য দিয়ে সরাসরি যায়।

## 🚀 ফিচারসমূহ

| ফিচার | বিবরণ |
|---|---|
| 🎨 Professional UI | সম্পূর্ণ Jetpack Compose-ভিত্তিক ইন্টারফেস |
| 🔢 Pairing Code | ৬-সংখ্যার সহজ পেয়ারিং কোড |
| 📷 QR পেয়ারিং | ইনস্ট্যান্ট QR স্ক্যান, ম্যানুয়াল কোড ফলব্যাকসহ |
| 📶 Auto Wi-Fi Join | QR-এ SSID/পাসওয়ার্ড থাকলে Viewer স্বয়ংক্রিয়ভাবে সংযুক্ত হয় |
| 🎥 H.264 এনকোডিং | হার্ডওয়্যার-ত্বরিত এনকোড/ডিকোড, কম ল্যাটেন্সি |
| 🛡️ Android 11–14 সাপোর্ট | Media Projection ও Foreground Service নিয়ম মেনে |
| 💾 Saved Profiles | ১০টি পর্যন্ত হটস্পট প্রোফাইল সংরক্ষণ (লোকাল, আপলোড হয় না) |
| 🧹 Safe Cleanup | Service, codec, socket, surface — সব লাইফসাইকেল সেফলি বন্ধ হয় |

## 📥 ইনস্টল করুন

সর্বশেষ APK ডাউনলোড করুন 👉 **[Releases পেজ](https://github.com/amarmuktagacha/ScreenLinkPro/releases/latest)**

দুই ফোনেই APK ইনস্টল করুন (Android 7.0+ সাপোর্টেড, তবে অপটিমাল অভিজ্ঞতার জন্য Android 11+ সুপারিশ করা হয়)।

## 📖 ব্যবহারবিধি

1. দুই ফোনেই অ্যাপ ইনস্টল করুন
2. দুটো ফোনকে একই Wi-Fi নেটওয়ার্ক বা হটস্পটে সংযুক্ত করুন
3. প্রথম ফোনে **"Share my screen"** চাপুন, ক্যাপচার পারমিশন দিন, ৬-সংখ্যার কোড কপি করুন
4. দ্বিতীয় ফোনে **"View another screen"** চাপুন এবং Host-এর QR কোড স্ক্যান করুন (বা ম্যানুয়ালি কোড/IP লিখুন)

> Host QR-এ Wi-Fi তথ্য থাকলে Viewer স্বয়ংক্রিয়ভাবে `WifiNetworkSpecifier` দিয়ে কানেক্ট করার চেষ্টা করবে। Android নিরাপত্তার জন্য একটা সিস্টেম কনফার্মেশন দেখাবে।

## 🛠️ বিল্ড (ডেভেলপারদের জন্য)

```bash
git clone https://github.com/amarmuktagacha/ScreenLinkPro.git
cd ScreenLinkPro
./gradlew :app:assembleDebug
```

Android Studio Hedgehog বা তার পরের ভার্সনেও প্রজেক্টটি সরাসরি খোলা যাবে। টার্গেট SDK 34, Kotlin/Java 17।

CI/CD স্বয়ংক্রিয়ভাবে প্রতিটি push-এ debug APK বিল্ড করে (`android-build.yml`), এবং প্রতিটি ভার্সন ট্যাগে (`v*.*.*`) একটা GitHub Release তৈরি করে APK অ্যাটাচ করে দেয় (`release.yml`)।

## 🔒 প্রাইভেসি

- কোনো ক্লাউড সার্ভার বা তৃতীয় পক্ষের ডেটা কালেকশন নেই
- স্ট্রিম সরাসরি লোকাল নেটওয়ার্কে ডিভাইস-টু-ডিভাইস পাঠানো হয়
- হটস্পট প্রোফাইল ডিভাইসের প্রাইভেট স্টোরেজেই থাকে

## 📄 লাইসেন্স

এই প্রজেক্টের কোনো লাইসেন্স ফাইল এখনো যোগ করা হয়নি।

---

<div align="center">
  <sub>Built with ❤️ using Kotlin & Jetpack Compose — entirely from an Android phone via Termux 📱</sub>
</div>
