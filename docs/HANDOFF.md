# 引き継ぎ資料 / Handoff (CorrectModeWidget)

新しいチャットは、まずこのファイルを読んでから作業を始めること。応答は**日本語**。

## 1. 概要
- Android アプリ(Kotlin)。リポジトリ: https://github.com/ghghuser/CorrectModeWidget ,ローカル: `/home/claude/correctmodewidget`(消えていたら clone)
- パッケージ `io.github.ghghuser.correctmode`,minSdk 34 / target 36
- マナーモード(着信音/バイブ/ミュート)と DND を組み合わせた「プロファイル」を、ホーム画面ウィジェット(1x1トグル / 切り替えウィジェット)で切り替える。タイマー、自動解除、予約オン、Bluetooth接続中のメディア許可あり
- Project「マナーモード切替ウィジェット製作」(説明: マナーモードとGoogleの間違いを正す)。ユーザーは「お嬢」(日本語)

## 2. ワークフロー
- ユーザーは直接コミット・push・Actions実行を許可済み。ただし**PRを使ってSonarCloud / Greptile / CodeRabbit にレビューさせてほしい**との希望
- 手順: origin/main から枝を切る → push → `gh api repos/ghghuser/CorrectModeWidget/pulls -f title=... -f head=... -f base=main -f body=...`(`gh pr create` は失敗した) → レビュー待ち(sleep は1コマンド120秒未満) → 指摘を直す → `gh api -X PUT .../pulls/N/merge -f merge_method=squash`
- Actions のビルドは main への push のみ(docs/, `*.md`, `*.txt`, LICENSE だけの変更ではビルドしない)。成果物名 `CorrectModeWidget-build<N>-debug.apk`。Maven 429 等の失敗は `gh api -X POST .../actions/runs/ID/rerun`
- squash merge 後のローカルは `git checkout -B 新枝 FETCH_HEAD`(古い枝をrebaseしない)
- ローカルでビルドできない場合は、strings の ja/en キー一致を確認して push → Actions で確認
- マージ済み: PR #1〜#7(#7 = de68c82、ビルド#34成功。バージョン表示を 2.0.(N÷100).(N%100) に変更、成果物は直近2つだけ残す)。成果物削除は一時的な502で1件残ることがあるが次回ビルドで消える

## 3. ユーザーが決めたルール(必ず守る)
- **鳴ってはいけない時に音が出ない**ことが最優先。挙動・セキュリティはラウンドごとにレビュー
- INTERNET 権限なし/非公開コンポーネントは exported=false/PendingIntent は FLAG_IMMUTABLE
- 署名鍵は固定(公開デバッグ鍵、パスワード android)。ビルド番号を成果物名に入れる
- 失敗理由は画面に表示する
- ja/en 両対応、strings にアポストロフィを使わない、`values` と `values-ja` のキーを完全一致させる
- ダークテーマ対応
- 用語: 「マナーモード」=着信音/バイブ/ミュート、「DND」=サイレントモード、「着信」ではなく「通話」
- ウィザードは「消す」(消音)側の聞き方。詳細は隠さずその場に表示
- ライセンス: 非商用・再配布禁止(LICENSE、README、設定画面のカード)
- 秘密情報: 必要になったら、アプリ完成後にリポジトリを private にして鍵を変える。値は GitHub Actions Secrets に置き、コードには書かない

## 4. 構成(`app/src/main/java/io/github/ghghuser/correctmode/`)
- `Model.kt`: `Profile`(ringer, dnd, 許可カテゴリ、背景 `bgOffA/bgOnA`、`keepRatio`、余白、オン用の `iconKeyOn/emojiOn/imageFileOn`、`releaseAll`、`tapVibe`(既定true)、予約 `sched*`、`preset`)。`Profile.fromJson` は旧 `Dnd.ALARMS` を PRIORITY+アラーム+メディアに変換。`Patterns.ALL`(テンプレート8種)
- `Store.kt`: 永続化。`seed`(初回のみ自分用2つ+テンプレ8)、名前移行、`busyUntil`、`lastError`(変化時に `Widgets.updateAll`)
- `Engine.kt`: 適用・解除・検証。解除はスナップショットへ復元。`releaseAll` は通常の着信音+DNDオフに戻す。外部変更検出 `endExternally`
- `Widgets.kt`: RemoteViews 描画。`bindItem(..., Margins OWN/LIST/NONE)`、`buildSwitch`(段数は `chooseRows`、角の結合 drawable `shape_join_*`)、グレー表示、⚠バッジ、`preview`
- `ProfileEditActivity.kt`: 動作カード(`buildChoice`, `buildPriorityOptions`, `refreshBehavior`)と、全画面ダイアログの見た目編集(`openLook`, `renderLook`)
- `WizardActivity.kt`(質問形式作成)、`WidgetConfigActivity.kt`(設置時設定、並び順▲▼)、`ColorPicker.kt`(不透明度スライダ付き)、`Ui.kt`(`chipsView`, `openBluetoothSettings`, `openPrioritySettings`)、`MainActivity.kt`、`SettingsActivity.kt`(診断、BT設定、ライセンス)、`Haptics.kt`、`TimeDialogActivity.kt`(通知の「時間」)、`ExpireReceiver`(アラーム処理: 期限/予約6/再確認5/WATCH8/REDRAW9)
- リソース: `themes.xml`(+night)に AppTheme/AppButton/AppDialog/LookDialog、`colors.xml`(btn_bg, btn_stroke, btn_text, accent)、`dialog_bg`、`btn_bg`、`widget_cell_outline`
- ドキュメント: README.md(利用者向け)、docs/REVIEW.md(ラウンド別の懸念と対処)、この HANDOFF.md

## 5. Android の挙動メモ(実機で確認したものを含む)
- Android 15+ の暗黙DNDルール: 非システムアプリは自分のルールしか操作できない。ringer SILENT にするとAndroidが自動でDND(アラームのみ)をオンにする。「すべてブロック」は、きれいな状態からだと先にPRIORITYルールを作る必要があり `waitForFilter` で確認(`blockAllEquivalent`)
- 解除はオン前のスナップショットに戻す。ユーザー設定のDNDはアプリのルールで置き換わることがあり、実機では解除で消えた(「消せない」と断言しない)
- **ミュート**では、優先で許可した通話も鳴らない(実機確認済み)。許可した通話を鳴らす/振動させたいならバイブ(または着信音)+通知フィルタ
- 「アラームのみ」=PRIORITY でアラーム+メディアのみ許可(通話・メッセージなし)
- 許可カテゴリ: 通話、メッセージ(相手 0なし/1全員/2連絡先/3スター付き)、メディア、BT接続時のみメディア、アラーム、システム音、リマインダー、予定。「優先度の高い会話」は**未実装**。「許可するアプリ」はAndroid設定でのみ(`Ui.openPrioritySettings`)
- RemoteViews: 単純なレイアウトのみ。スペーサーは空FrameLayout。行は入れ子RemoteViews。`setColorFilter` は SRC_ATOP で半透明にならないため、不透明色+`setInt("setImageAlpha")` を使う。サイズは AppWidgetManager options(`boxDp`)から
- 振動: `Haptics.tap` は250ms遅延+`USAGE_ALARM` の `vibrate(effect, AudioAttributes)`。以前振動しなかった原因は未確定

## 6. 実機での確認状況
確認済み(ユーザー報告): ミュート/すべてブロックの3項目「問題なし」、バイブ+優先で許可した通話が振動、ミュート系2種でアラームが止まる、アラームのみDNDでアラーム/メディアが鳴る、SonarCloud は0件
未確認: タップ振動、不透明度、オン/オフ別画像、リスト余白の統一、新しい見た目編集ウィンドウ、新テーマ(昼夜)、一覧の並び順、変換後の「アラームのみ」、⚠マーク(外部変更でも出るよう修正済み)

## 7. 既知の未修正
- 設定の診断クリア時にウィジェットが二重再描画される(軽微)
- 「優先度の高い会話」未実装
- よく使う優先パターンのテンプレート追加はユーザーの希望次第

## 8. 次にやること(ユーザーに依頼する実機確認、優先順。解決するまで繰り返す)
1. 見た目編集ウィンドウ: 3タブ、色/アイコン/画像(オフ・オン)の編集、プレビュー追従、保存
2. 通知フィルタ詳細: 他のDND選択でグレーになるか。バイブ+通話許可で振動するか
3. 変換された「アラームのみ」プロファイルでアラームが鳴るか
4. 新しい配色(昼/夜): ボタン、ダイアログ、ウィザード、時刻ピッカー
5. 切り替えウィジェットの並び順(▲▼)が表示とトグル順に反映されるか
加えて: タップ振動、不透明度、余白、⚠マーク。
結果を受けたら修正→PR→マージ→ビルド確認→再度チェック依頼、のサイクルで進める。
