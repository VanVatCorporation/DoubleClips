<div style="display: flex; align-items: center; justify-content: space-between; font-family: sans-serif; padding: 10px;">
  <div style="flex: 1; padding-right: 20px;">
    <h1 style="margin: 0; font-size: 2em; color: #666;">DoubleClips</h1>
    <p style="margin: 5px 0 0; font-size: 1.1em; color: #555;">
      🎥 A free, open-source, cross-platform video editor for creators.
    </p>
    <p style="margin: 5px 0 0; font-size: 0.9em; color: #888;">
      Built with ❤️ for creators, coders, and communities.
    </p>
  </div>
  <div style="flex-shrink: 0;">
    <img src="src/main/res/drawable/logo.png" alt="DoubleClips Logo" width="100"
      style="border-radius: 8px; box-shadow: 0 0 8px rgba(0,0,0,0.05);" />
  </div>
</div>


DoubleClips is an open-source, ready-to-use video editor with functionality similar to CapCut. It offers Community Templates, Cross-platform rendering, Multitrack editing, and a variety of effects!



[<img src="https://img.buymeacoffee.com/button-api/?text=Feed%20me&emoji=%F0%9F%8D%9A&slug=viet2007ht&button_colour=FFDD00&font_colour=000000&font_family=Cookie&outline_colour=000000&coffee_colour=ffffff" alt='Feed me' height="60">](https://www.buymeacoffee.com/viet2007ht)
[<img height='50' style='border:0px;height:50px;' src='https://storage.ko-fi.com/cdn/kofi6.png?v=6' border='0' alt='Buy Me a Coffee at ko-fi.com' />](https://ko-fi.com/W7W11RA9FH)

## ⚠️ Attention
I’m pausing the development of this project due to a backlog of homework and some financial issues I need to handle. For now, I won’t be adding any new features, but I’ll be back!

## ⚙️ Install
[<img src="./assets/get-it-on-github.png" alt='Get it on GitHub' height="80">](https://github.com/VanVatCorporation/DoubleClips/releases/latest)
[<img src="./assets/google-play-badge.png" alt='Get it on Google Play' height="80">](https://play.google.com/store/apps/details?id=com.vanvatcorporation.doubleclips)

## 🚜 Current Features

- Real-time preview (Early access).
- Audio & Video time precise seeking.
- Export project.
- Smooth transitions.
- Keyframe editing.
- Properties Importing/Exporting (Keyframe, Clip, Track info, export as JSON)
- Lots of properties (Reverse, Speed, Opacity, Temperature, Hue, Saturation, Brightness,...)
- Sharable templates (Beta Early access.).
- Edit raw FFmpeg command (Gen Cmd and you're free to edit whatever you want. Not for normal users as it require FFmpeg command experience).
- Render cross-platform (By saving it as a zip file including the JSON timeline, transfer it to your PC, or other stronger devices, and use it to render the clip, you can easily render across platforms).
- Full control over export parameters (including Constant Rate Factor, Width, Height, Framerate and etc. Allows you to input any value you prefer. Higher settings may take longer to render. The limitations depend on the devices used. Yup, we can render 4k, 8k, 16k or whatever k you want).
- Multitrack editing.
- No watermarks or subscriptions.
- Support both Time-based and Frame-based editing style.
- Profiles and status (Alpla Early access. Avatar and display username.).
- ~~Transactions~~ (Not what you might think! You can upload the template, set the price for people to buy, and earn 80% of the transactions. Purchased templates can be customized however the buyers want. It’s literally their right to own it.).

## ✅ Requirements

### 👨‍💻 Windows / MacOS / Linux

- We are currently working on the Android version, so it isn't available yet.
   
### 📱 iOS

- We are currently working on the Android version, so it isn't available yet.

### 📱 Android

- Minimum SDK: 24 (Nougat, Android 7.0)
- FFmpeg Support (You can check if your device supports it by opening the app).

And that's actually it! High-end devices render quickly, low-end devices render slowly, but the functionality remains the same!

## 🤝 Contributing

We’re excited for contributions! While we’re busy developing and refactoring certain parts, there are still plenty of great ways to help out. 

~~Check out our Contributing Guide for setup steps, dev guidelines, and full focus area details.~~

Quick start: 
 - Fork the repo, clone it locally.
 - ~~Follow CONTRIBUTING.md~~
 - Create a feature branch, and open a PR.

## 🚀 Roadmap


### 🎥 Actually using Rajawali to edit 3D scenes? 

Right now, the project includes Rajawali, but it isn’t fully functional across the app. The idea is to let you modify 3D scenes and edit them like any other Clip instance, with Rajawali’s JSON structure attached. Once implemented, DoubleClips should be able to use Rajawali alongside the traditional FFmpeg, potentially supporting 3D text and other features, making the app more powerful for video editing.

### 🤖 AI-generated content? 

If you’ve used CapCut, you might have noticed some templates now support it. Guess what? DoubleClips can do it too! However, running text generation AI takes a lot of resources, and our current machine can’t handle it on its own. So how would it manage a single prompt for a video? Without a powerful server, we need your help to tame this beast, upgrade the API, and more. Check out the *Donate* section below for details.

### 💼 iOS/MacOS/Windows/Linux Support?

Of course, we plan to expand beyond Android. Imagine not needing a mobile device to use the template system, unlike CapCut (correct me if I’m wrong). With DoubleClips, any device will have the same functionality, with the only difference being video rendering speed. You also won’t need a CapCut subscription for cloud storage to move projects from mobile to PC. Instead, you can simply export your project as a zip file, transfer it to your PC, and reimport it—switching between platforms with just a few clicks!

### 🎬 Version Control like Github? Team Chat, Realtime remote edit like Figma, Canva?

Imagine DoubleClips as a cross-platform video editor that integrates version control like GitHub, supports private team projects for organizations, includes built-in team chat, and offers real-time collaborative editing with visible cursors, just like Google Docs, Figma, or Canva. It could become a more advanced yet free video editing software, combining these powerful features to enhance teamwork and creativity.

### ✨ Split Application Distribution?

Right now, DoubleClips is being developed by just one person, but in the future, anyone will be able to contribute, so the project could be split into two versions. Similar to Visual Studio Professional and Visual Studio Community, we could have DoubleClips and DoubleClips Community. DoubleClips would be distributed through all major stores like Google Play Store (Android), App Store (MacOS/iOS), and Microsoft Store (Windows), while DoubleClips Community would be available via its own GitHub repository and our website, with .apk for Android, .exe for Windows, and .dmg for MacOS. DoubleClips could also adopt the Community version’s distribution methods. New features from DoubleClips Community could be added to DoubleClips if users vote in favor, and if more than 50% agree, the feature’s code would be reviewed by our supervisor for malware or harm before being released across all stores with proper attribution. For example, in the Google Play Store’s “New features” section:

**[New features]**
```
[Main]
New feature 1.
New feature 2.
[Community]
[some-nice-guy-in-github-1] Add some new feature.
[some-nice-guy-in-github-1] Optimize code.
[some-nice-guy-in-github-2] Fix Track.
```
Some stores may limit the length of new feature descriptions, so our website would display the full list in the same format.



## 💰 Donate

Most open-source projects are nearly non-profitable, which means we rely on your support to survive this harsh reality. Instead of asking for subscriptions — no, we don’t do that here — why not consider donating so the project can develop faster?

Send me a cup of coffee via:
- Ko-fi: https://ko-fi.com/viet2007ht
- Buy me a coffee: https://buymeacoffee.com/viet2007ht
- PayPal: https://www.paypal.me/nguyenviet163
- Bitcoin: bc1ql5mztnx7vaz2pw9qqv9a76pflx8lw3u68fxnxe
- Solana: GfsHviuLtKWuX6jVLh9pCup2w36bmZgTFbsN6K3RowxE
- USDT: 0x40D247A8d5a9733613BbEB857bB34Ee051e2BB22


## 📃 License

[GNU LICENSE](LICENSE)

---
## ⭐️ Star History

![Star History Chart](https://api.star-history.com/svg?repos=VanVatCorporation/DoubleClips&type=Date)

