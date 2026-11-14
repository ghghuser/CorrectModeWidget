# アイコンの差し替え / Replacing the app icon

ここに **`icon.svg`** または **`icon.png`** を置いて `main` に push すると、GitHub Actions がビルド時にアプリのアイコン(ランチャーアイコン)へ変換します。
Put **`icon.svg`** or **`icon.png`** in this folder and push to `main`: the build converts it into the launcher icon.

- 正方形、PNGは 512x512 以上を推奨 / Square, PNG 512x512 or larger recommended.
- **背景が透明**の画像: 中央に(アダプティブアイコンの安全域に収まるよう縮小して)置き、背景はアプリの青にします。
  **背景が不透明**の画像: 全面に敷きます(端は端末のマスク形状で切り取られます)。
  Transparent image: shrunk into the safe zone on the app\'s blue. Opaque image: used full-bleed (edges are cut by the device mask).
- `icon.svg` と `icon.png` の両方がある場合は `icon.svg` を使います / If both exist, `icon.svg` wins.
- 変換に失敗してもビルドは止まらず、標準のアイコンのままになります(Actionsのログに理由が出ます)。
  If the conversion fails the build still succeeds with the default icon (the reason is in the Actions log).
- ウィジェットのボタンの絵は、このアイコンとは別で、アプリ内のプロファイル編集画面で選びます。
  The pictures on the widget buttons are separate: choose them in the profile editor.
