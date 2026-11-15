# アイコンの差し替え / Replacing the app icon

ここに **`icon.svg`** または **`icon.png`** を置いて `main` に push すると、GitHub Actions がビルド時にアプリのアイコン(ランチャーアイコン)へ変換します。
Put **`icon.svg`** or **`icon.png`** in this folder and push to `main`: the build converts it into the launcher icon.

- 正方形、PNGは 512x512 以上を推奨 / Square, PNG 512x512 or larger recommended.
- **背景が透明**の画像: 中央に(アダプティブアイコンの安全域に収まるよう縮小して)置き、背景はアプリの青にします。
  **背景が不透明**の画像: 全面に敷きます(端は端末のマスク形状で切り取られます)。
  Transparent image: shrunk into the safe zone on the app's blue. Opaque image: used full-bleed (edges are cut by the device mask).
- `icon.svg` と `icon.png` の両方がある場合は `icon.svg` を使います / If both exist, `icon.svg` wins.
- 変換に失敗してもビルドは止まらず、標準のアイコンのままになります(Actionsのログに理由が出ます)。
  If the conversion fails the build still succeeds with the default icon (the reason is in the Actions log).
- ウィジェットのボタンの絵は、このアイコンとは別で、アプリ内のプロファイル編集画面で選びます。
  The pictures on the widget buttons are separate: choose them in the profile editor.

## ウィジェット選択画面のプレビュー / Widget picker preview

ウィジェット一覧(「CorrectMode トグル」「CorrectMode 切り替え」)に出る絵は、標準ではアプリアイコンです。
別の絵にしたいときは **`icon/widget_preview.png`** を置いて push します(任意)。
The picture shown in the widget picker is the app icon by default. To use another picture, add **`icon/widget_preview.png`** (optional).

- 形式: **PNG**(透明可)、**正方形**、**512x512 px 前後**(1024以下)、1MB以下 / Format: PNG (transparency OK), square, about 512x512 px (max 1024), under 1 MB.
- ランチャーによって角丸などで切り抜かれることがあるので、絵は中央に、余白を10%ほど残してください / Launchers may crop or round it: keep the picture centred with about 10% margin.
- 2つのウィジェット(トグル/切り替え)で同じ絵を使います / Both widgets share the picture.
