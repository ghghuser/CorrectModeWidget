# 引き継ぎ資料 / Handoff (CorrectModeWidget)

新しいチャットは、まずこのファイルと `docs/PROGRESS.md` を読んでから作業を始めること。応答は**日本語**。同じ依頼が再送されても、PROGRESS の状況確認から続きを進める(最初からやり直さない)。

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
- マージ済み: PR #1〜#12(#12 = cacf18c、ビルド#40成功)。バージョン表示は 1.0.(N÷100).(N%100)(例 #1010 → v1.0.10.10)。成果物は直近2つだけ残す(削除が一時的な502で1件残ることがある)。PRのコンパイル確認は workflow_dispatch(`gh api -X POST .../actions/workflows/ID/dispatches -f ref=枝名`)で枝のビルドを走らせられる

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

## 8. 次にやること
**`docs/PROGRESS.md` が作業キューと進捗ログ(最新)。必ずそちらを先に読み、チェック済みの項目をやり直さない。**

## 9. 診断ウィザード(PR #11, #12)と設計メモ
- 「質問に答えながら作成」= 「適正モード診断」(`WizardActivity`)。質問: ①着信音と通知音(いる/いらない/場合による) → ②振動(①が「いる」なら飛ばす。端末の「バイブレーションとハプティクス」はアプリから操作しない) → ③Bluetooth音声(はい/いいえ、はいの時だけ対象: すべて/ヘッドセット・ヘッドホン・イヤホンのみ) → ④聞こえてもいい音(なし/全部/個別。個別はまとめて通知フィルタ1つ) → ⑤自動解除 → 結果(最大3件)
- 「場合による」は該当する全ケースを作る(着信音あり=通常、なし+振動=バイブ、なし+振動なし=ミュート)。同じ内容(音の動作+自動解除の時間が同じ)の自分用プロファイルがあれば作らない。複数件のときは予約オンを設定しない
- 「なし」を明示した時だけすべてブロック(Bluetoothありなら「BT音声のみ許可」)。個別で何もチェックしない場合は通知フィルタ+着信音設定のまま(許可アプリだけ通す)
- ミュートでは許可した通話も鳴らないため、ミュートの案では通話/メッセージをグレー表示し、押すと「振動の答えを変更しますか?」を出す。ミュート案では保存時に通話・メッセージを0にする
- `Profile.btScope`(プロファイルごとのBluetooth対象)。`Bt.audioConnected(ctx, scope, ...)`。`wouldBeLouder` は ヘッドセットのみ → すべて を「大きくなる」と判定
- テンプレートは16種(`Patterns.LIST`): マナーモード3 × DND(オフ/通知フィルタ/すべてブロック)、通知フィルタ×Bluetooth(なし/すべて/イヤホンのみ)、BT音声のみ許可2種、アラームのみ。名前は仮(`pat_1`〜`pat_16`)。ユーザーが後で変更する予定
- 既知: 旧バージョンのバックアップ/保存済みプリセット番号(`pre`)は新テンプレート番号と対応しない(新規インストール前提のため未対応)。編集保存後に適用が失敗した場合、保存済み定義が編集後のまま残る(PR #11 の CodeRabbit 指摘、画像ファイル削除順序の整理が必要。未対応)
- 未確認: ウィザード全体、BT対象のプロファイル別設定、16テンプレート

## 10. 【重要】GitHub Codespaces 利用ルール(ユーザー指定)
ユーザーのアカウントは今月のCodespaces無料枠(コア時間・ストレージ)を100%超過。ユーザーはCodespacesを操作したことがなく、プログラミングは全てClaudeに任せている。
1. Codespacesを新規作成・起動しない。今月は使えない前提。必要になりそうなら、作業を始める前にユーザーへ知らせる。
2. ビルド・テストは GitHub Actions。編集はリポジトリへの直接コミット・PR。ユーザーがローカルで何か実行する必要がある場合は、初心者向けに1ステップずつ書く。
3. 来月以降に使う場合: 最小の2コア、作業後は必ず停止し不要なものは削除(削除前にブランチへpush)、Prebuildなし、重いビルド/テストはActionsへ。
4. 起動中・残っているCodespaceに気づいたら、停止・削除をユーザーへ提案。(この環境では `gh api user/codespaces` が403で一覧不可。その旨を伝える)
不明点・迷う点は作業前に質問する。

## 11. 作業の進め方(再開耐性)
- 1ステップ終えるごとに `docs/PROGRESS.md` を更新して docs のみ push(docs-only なのでビルドされない)。`git log`・PR一覧・Actions の状態を確認してから動く
- 実機確認は優先順に約5項目ずつ依頼。結果→修正→PR→マージ→ビルド確認→再依頼。未確認の推測は「未確認」と明記
- Project docs への反映: `Projects project_write` の `local_path` はワーキングディレクトリ内の絶対パス(例 `/home/claude/correctmodewidget/.tmp.md`)でのみ可。書いたら一時ファイルを削除
- 記憶(memory)は `/projects/01a1c610-1d31-72d3-931c-a635cfb83628/` 配下のみ書ける
- 追加仕様(Android.txt)のうち常時適用: 作業を細かく区切る/リポジトリ直下を最小に/READMEの開発者向けは最下段に簡素に/成果物は直近2つ/docsのみはビルドしない/アイコンは icon.svg・png/ダークテーマ/言語設定/文体統一(話し言葉・命令文を避ける、体言止め可)/APK名 `<app>-build<N>-debug.apk`/最後に数回に分けて穴・バグを点検
