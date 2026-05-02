# 🧜‍♀️ erdMaid: 개발 기준 요약

이 문서는 IntelliJ Platform 플러그인 `erdMaid`를 구현할 때 따라야 할 기준을 정리합니다.

## 1. 프로젝트 목표

- IntelliJ Database 도구 창에서 선택한 테이블 메타데이터를 추출한다.
- 추출한 정보를 Mermaid `erDiagram` 문법으로 변환한다.
- 결과를 클립보드에 복사하고, 완료 알림을 띄운다.

## 2. 구현 원칙

- 언어는 `Kotlin`을 사용한다.
- 빌드 시스템은 `Gradle`과 IntelliJ Platform Gradle Plugin을 사용한다.
- `com.intellij.database` 의존성은 반드시 포함한다.
- 대상 UI는 Database 도구 창의 우클릭 컨텍스트 메뉴(`DatabaseViewPopupMenu`)이다.
- `classDiagram`이 아니라 정식 `erDiagram` 문법만 사용한다.
- 실제 DB의 컬럼 순서를 유지한다.
- PK/FK 관계, 데이터 타입, 컬럼 코멘트를 포함한다.

## 3. 핵심 역할

### Architect

- `plugin.xml` 설정을 관리한다.
- `AnAction` 진입점을 정의한다.
- 플러그인 수명 주기와 의존성을 관리한다.

### Metadata Extractor

- `DasTable`, `DasColumn`을 탐색한다.
- `DasUtil`로 실제 컬럼 순서(Ordinal Position)를 확보한다.
- Primary Key와 Foreign Key 정보를 추출한다.

### Mermaid Generator

- 추출한 메타데이터를 Mermaid 문자열로 변환한다.
- 테이블 블록, 컬럼 블록, 관계선을 조립한다.
- 데이터 타입의 공백 및 특수문자를 정리한다.

### UX & Integration

- 결과를 클립보드에 복사한다.
- 성공/실패 알림을 표시한다.
- 선택된 요소가 없을 때는 동작하지 않거나 안내한다.

## 4. 현재 구현 범위

### 필수

- `build.gradle.kts`에 Database API 접근 설정 추가
- `plugin.xml`에 `com.intellij.database` 의존성 추가
- `plugin.xml`에 `ErdMaidExportAction` 등록
- `ErdMaidExportAction.kt` 구현
- `MermaidGenerator.kt` 구현

### 동작 규칙

- 선택된 대상 중 테이블만 처리한다.
- 테이블 코멘트가 있으면 Mermaid 주석(`%%`)으로 넣는다.
- 컬럼 출력은 `타입 컬럼명 [PK] ["코멘트"]` 순서를 유지한다.
- 타입명 내 공백은 `_`로 치환한다.
- 컬럼 코멘트 내 큰따옴표는 안전하게 이스케이프한다.
- FK 관계는 선택된 테이블 집합 안에 존재하는 관계만 출력한다.

## 5. 우선순위 로드맵

### Phase 1: Foundation

- `com.intellij.database` 의존성 연결
- `plugin.xml` 액션 등록
- 테이블 이름 추출 및 선택 처리

### Phase 2: Metadata Deep Dive

- `DasUtil` 기반 컬럼 추출
- 컬럼 데이터 타입/코멘트 매핑
- Primary Key 식별

### Phase 3: Relationship Mapping

- Foreign Key 정보 추출
- 부모-자식 테이블 관계 생성
- Mermaid 관계선 기호 매핑

### Phase 4: Final Polish

- 타입/코멘트 sanitize
- 클립보드 복사 및 완료 알림
- `markflow` 연계는 후속 확장 과제로 검토

## 6. 제외 또는 후순위

- `classDiagram` 지원은 제외한다.
- 뷰(View) 지원은 우선순위를 낮춘다.
- `markflow` 연동은 현재 필수 요구사항이 아니다.

---
*erdMaid Development Team*
