# 배운 것들 
## JPA에서 엔티티를 읽어올 때 

"엔티티 생성자에서 검증"
- `INSERT` 시 : 우리가 직접 new 할 때 검증됨✅
- `READ` 시 : DB -> 객체로 변환될 때도 검증됨 

DB row -> **엔티티 객체 생성**  
즉, **읽을 때도 "객체를 새로 만드는 과정"이 있다**  

실제 : JPA는 보통 생성자를 안씀  

JPA 기본 동작 기준  
👉 생성자 검증만으로는 READ 검증 안 된다. 

READ 검증 
```java
@PostLoad
private void validateAfterLoad() {
    validateTimeRange();
}
```
---
## 생성자를 private으로 두고, 생성자를 호출하는 팩토리 메서드를 만들자
### "객체는 항상 생성 시점에 검증되어야 한다."
```java
private TimetableSlot(...) {
    validate();
}

public static TimetableSlot of(...) {
    return new TimetableSlot(...);
}
```

### 하지만 이렇게 만들어도..
DB에서 JPA가 읽어올 때는 저 팩토리 메서드를 타지 않는다. 
1. 기본 생성자 호출 (`@NoArgsConstructor`)
2. 리플렉션으로 필드 직접 주입 
```java
TimetableSlot slot = new TimetableSlot(); // 기본 생성자
// reflection으로 필드 세팅
```
#### INSERT (내가 new 할 때)
- 팩토리 메서드 사용 -> 검증됨 
#### READ (DB에서 조회할 때)
- 팩토리 메서드 안 탐
- private 생성자도 안 탐  
👉 그냥 필드 박힘 