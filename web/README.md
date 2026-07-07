# Nosmai Moderation + Agora - Web Example

Agora live streaming with on-device Nosmai content moderation, running entirely
in the browser. Agora captures and publishes the camera; each sampled frame is
moderated by the Nosmai Moderation Web SDK (NSFW + object detection), and chat
text can be moderated too. Nothing about the moderation leaves the browser.

## Prerequisites

- **Node.js** 18+ and npm.
- A **Nosmai Moderation** license key registered for the page origin you run on
  (for local dev that is `http://localhost:5173/`), from https://nosmai.com/.
  The `@nosmai/moderation-web` package and the encrypted models come with the
  license.
- An **Agora** App ID and a temporary token (Agora Console), or a token server.

## Setup

1. Install dependencies:

   ```sh
   cd web
   npm install
   ```

2. Put the encrypted model files (provided with your license) in
   `web/public/models/`:

   ```
   public/models/nsm_nsfw.onnx
   public/models/nsm_detector.onnx
   public/models/nsm_text.onnx
   public/models/vocab.txt
   ```

3. Copy `.env.example` to `.env.local` and set the placeholders:

   - `VITE_NOSMAI_LICENSE_KEY` - your origin-bound `NOSMAI-XXXX` key.
   - `VITE_AGORA_APP_ID`, `VITE_AGORA_CHANNEL`, `VITE_AGORA_TOKEN` - your Agora project values
     (leave `VITE_AGORA_TOKEN` empty for a testing-mode project).

4. Run the dev server:

   ```sh
   npm run dev
   ```

   Open `http://localhost:5173/`, press **Start stream**, and allow camera
   access. The verdict (SAFE / UNSAFE) updates live; detected objects are boxed
   on the preview. Use the text field to moderate chat messages.

> The Agora temporary token expires (about 24 hours). If the join fails with a
> token error, regenerate the token in the Agora Console.

## How it works

`AgoraRTC.createCameraVideoTrack()` captures the camera and `client.publish()`
streams it. The same track feeds a `<video>` element; a loop samples that video
(about once a second) and calls `NosmaiModeration.analyzeImage(video)`. The SDK
runs NSFW and object detection on-device and returns a verdict, which the app
renders over the preview.

## License

The example code in this repository is MIT (see the repository [LICENSE](../LICENSE)).
The Nosmai Moderation SDK and its models are proprietary and require a license
key; Agora is a separate commercial SDK.
