> [!IMPORTANT]
> **Historical document — superseded and not authoritative.**
>
> This is the original April 2026 implementation prompt that was used to bootstrap
> erdMaid. It is kept only as a record of the initial intent. It has since diverged
> from the shipped code in at least the following ways:
>
> - it specifies IntelliJ Platform Gradle Plugin **1.x** (`plugins.set(...)`), while the
>   project is on IJPGP 2.x with the `intellijPlatform { }` dependencies extension;
> - it describes foreign-key extraction via `DasUtil.getForeignKeys` only, while
>   `RelationResolver` prefers `ModelRelationManager.getForeignKeys(project, table)` and
>   falls back to `DasUtil` only when no `Project` is available;
> - it does not describe relation de-duplication, column-reference rendering, entity-name
>   quoting, or the type precision/scale/length suffix rules that `MermaidGenerator`
>   actually implements.
>
> **The authoritative engineering contract is [`AGENTS.md`](../../AGENTS.md).** Do not
> treat anything below as a current requirement.

---

# 🛠️ Step-by-Step Implementation Plan

## 1. Project Configuration (build.gradle.kts & plugin.xml)

### build.gradle.kts

intellij 설정 블록에서 `plugins.set(listOf("com.intellij.database", "com.intellij.modules.platform"))`를 추가하여 Database API 접근 권한을 확보한다.

### plugin.xml

- `<depends>com.intellij.database</depends>` 명시
- `<actions>` 블록에 `ErdMaidExportAction`을 등록하고 `<add-to-group group-id="DatabaseViewPopupMenu" anchor="last"/>`를 설정한다.

---

## 2. Action Entry Point (ErdMaidExportAction.kt)

`AnAction`을 상속받는 클래스를 생성한다.

### update(e: AnActionEvent) 로직

1. `e.getData(PlatformDataKeys.PSI_ELEMENT_ARRAY)`를 통해 현재 선택된 요소들을 가져온다.
2. 선택된 요소 중 `DbTable`(또는 `DasTable`) 인터페이스를 구현한 객체가 1개 이상일 때만 메뉴가 활성화(`e.presentation.isEnabledAndVisible = true`)되도록 처리한다.

### actionPerformed(e: AnActionEvent) 로직

1. 선택된 `DbTable` 객체 리스트를 추출하여 `MermaidGenerator` 클래스로 전달한다.
2. 생성된 `String` 결과를 `CopyPasteManager.getInstance().setContents(StringSelection(result))`를 사용하여 클립보드에 복사한다.
3. `NotificationGroupManager`를 통해 "Mermaid ERD copied to clipboard" 성공 알림을 띄운다.

---

## 3. Metadata Extraction & Mermaid Generation (MermaidGenerator.kt)

이 플러그인의 핵심 로직입니다. `DasUtil`을 적극 활용하여 텍스트를 조립합니다.

- **입력:** `List<DasTable>`
- **출력:** `String` (Mermaid erDiagram 문법)

### 생성 로직 (Kotlin buildString 활용 권장)

#### A. 문서 시작

최상단에 `erDiagram` 선언 (줄바꿈 포함).

#### B. 테이블 구조 & 코멘트 조립 (DasTable 순회)

1. `[테이블 코멘트]` — `table.comment`를 추출한다. Mermaid는 테이블 코멘트 전용 문법이 없으므로, 값이 존재할 경우 테이블 블록 시작 전 윗줄에 `%% [테이블 코멘트]` 형태로 주석 처리하여 삽입한다.
2. `table.name`을 이용해 `TABLE_NAME {` 형태로 블록을 연다.
3. `[컬럼 추출]` — `DasUtil.getColumns(table)`을 호출하여 컬럼 목록을 가져온다. (이 메서드는 DB의 실제 Ordinal Position을 보장함).

각 `DasColumn`에 대해 다음 속성을 추출한다:

| 속성 | 설명 |
|------|------|
| `name` | 컬럼명 |
| `dataType.typeName` | 데이터 타입. **(주의:** 공백이 포함된 타입명(예: `INT UNSIGNED`)은 공백을 언더스코어(`_`)로 치환해야 Mermaid 문법 에러가 발생하지 않음) |
| `comment` | 컬럼 코멘트. **(주의:** 내부의 큰따옴표(`"`)를 홑따옴표(`'`)로 치환한 뒤, 전체를 큰따옴표(`""`)로 감싸서 출력해야 함) |
| PK 여부 | `DasUtil.getPrimaryKey(table)`의 컬럼 목록에 포함되는지 확인하여 맞다면 PK 문자열 추가 |

**포맷팅 규칙 (컬럼):**
```
    [타입] [컬럼명] [PK] ["[컬럼 코멘트]"]
```
들여쓰기 4칸, 순서 엄수.

#### C. 관계 (Foreign Key) 조립

1. 테이블 순회가 끝난 후, 다시 `DasTable` 리스트를 순회하며 `DasUtil.getForeignKeys(table)`를 호출한다.
2. 각 `DasForeignKey`에서 참조하는 부모 테이블(`refTableName`)과 현재 자식 테이블 이름을 가져온다.

**포맷팅 규칙:**
```
    [부모테이블] ||--o{ [자식테이블] : "[FK이름]"
```

선택된 테이블 목록(`List<DasTable>`) 내에 존재하는 테이블끼리의 관계만 출력하도록 필터링한다.

---

## 4. Edge Cases to Handle

- 코멘트가 `null`이거나 비어있을 경우, 테이블 주석(`%%`)이나 컬럼 코멘트(`""`) 문자열 덧붙이기를 생략할 것.
- 선택된 요소가 `DbTable`이 아닌 뷰(View)일 경우의 예외 처리 (무시하거나 뷰 컬럼 추출 지원).

---

## 📋 지시사항 (To AI Coder)

위 명세를 완벽히 숙지하고 다음 파일들의 완전한 소스 코드를 구현해 줘:

- `plugin.xml`
- `build.gradle.kts` (핵심 의존성 부분)
- `ErdMaidExportAction.kt`
- `MermaidGenerator.kt`
