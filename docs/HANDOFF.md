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
- マージ済み: PR #1〜#10(#10 = 6162ca1、ビルド#37成功)。バージョン表示は 1.0.(N÷100).(N%100)(例 #1010 → v1.0.10.10)。成果物は直近2つだけ残す(削除が一時的な502で1件残ることがあるが次回ビルドで消える)

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
確認済み(ユーザー報告): ミュート/すべてブロックの3項目、バイブ+優先で許可した通話が振動、ミュート系でアラームが止まる、アラームのみDNDでアラーム/メディアが鳴る、見た目編集ウィンドウ、通知フィルタ詳細、変換済み「アラームのみ」、新配色、並び順(▲▼)、不透明度、余白、SonarCloud 0件
実機で判明した不具合(PR #8 で修正、**修正後は未確認**):
- オン中のウィジェットを押すと1回目はグレー後にオンへ戻り、2回目で解除(すべてブロック以外)。原因の推定: タップ時の `Engine.reconcile` が外部変更と判定→状態が消える→同じタップでもう一度オンになった。解除のタップでは reconcile を呼ばないようにした(切り替えでは従来どおり呼ぶ)
- プロファイル一覧でチップ行をタップしても編集が開かない。HorizontalScrollView がタップを吸っていた。チップ(子ビュー)にリスナーを移した
- タップ振動: 端末の「アラームのバイブレーション」がオンのときだけ振動する(仕様の注記を追加)。オン時は以前振動しなかったため、オンにする前(切り替え時は後)に振動させる。**未確認**
⚠マークの出る条件(ユーザーへ説明済み): `Store.lastError` がある間。外部変更の検出(音量キー、クイック設定、他アプリでマナーモード/DNDが変わりセッションが終了)、解除の失敗、DND権限なし、適用の失敗など。診断画面で理由を確認できる
未確認: 上記3点の修正、未保存警告、ボックスボタン、追加ダイアログ、システム配色の初回許可ダイアログ

## 7. 既知の未修正 / 方針
- 旧設定との互換処理は削除済み(新規インストール前提)。保存済みの dnd=2 のプロファイルは読み込まず捨てる
- 設定の診断クリア時にウィジェットが二重再描画される(軽微)
- 「優先度の高い会話」未実装。許可するアプリは「DND(モード)の詳細設定を開く」から端末側で設定(アプリ直接は開けない)
- 臆測で入れた可能性がある処理(見直し候補。実機で問題が出るまで残す): TapActivity の連打ガード(2秒)、Engine の 300ms 待ち、解除後 250ms 遅延の振動、Bluetooth 切断後の再確認

## 8. 次にやること(ユーザーに依頼する実機確認、優先順)
1. ウィジェットでオン→1回のタップで解除できるか(複数のテンプレート)
2. プロファイル一覧でチップ行・名前・概要のどこをタップしても編集が開くか
3. 編集画面: マナーモードのボックスボタン、DND(モード)の並びと絵文字、一覧の絵文字の位置、スワイプ戻りの未保存警告
4. ウィジェット設定から「アプリを開く」の未保存警告、追加ダイアログの並び、テンプレートタブで追加ボタンがグレーか
5. 初回許可ダイアログがシステム配色か(アプリデータ消去後に確認)
加えて: タップ振動(オン/解除)、一覧の絵文字の隙間、⚠マークが出たときの診断画面の内容。
結果を受けたら修正→PR→マージ→ビルド確認→再度チェック依頼、のサイクルで進める。
