# كيفية إخفاء بعض الـ Commits والاحتفاظ ببعضها فقط

هناك عدة طرق لتنظيف تاريخ Git قبل الرفع إلى GitHub:

## الطريقة 1: Interactive Rebase (الأفضل للتحكم الكامل)

### خطوات:

1. **ابدأ interactive rebase من commit معين:**
   ```bash
   git rebase -i <commit-hash-before-first-commit-you-want-to-keep>
   # أو
   git rebase -i HEAD~5  # للـ 5 commits الأخيرة
   ```

2. **في المحرر الذي يفتح، ستجد قائمة بالـ commits:**
   ```
   pick a63a83e repo initialization
   pick aa11c46 Add repository setup guide
   pick 14567b3 Add quick start guide
   pick 23a72da Add GitHub Actions workflow
   pick f6be8d6 Initial commit
   ```

3. **استبدل `pick` بالأوامر التالية:**
   - `pick` - احتفظ بالـ commit كما هو
   - `drop` أو `d` - احذف الـ commit
   - `squash` أو `s` - ادمج مع الـ commit السابق
   - `edit` أو `e` - عدّل الـ commit
   - `reword` أو `r` - غيّر رسالة الـ commit

4. **مثال: للاحتفاظ فقط بـ "Initial commit" ودمج الباقي:**
   ```
   pick f6be8d6 Initial commit
   squash 23a72da Add GitHub Actions workflow
   squash 14567b3 Add quick start guide
   squash aa11c46 Add repository setup guide
   squash a63a83e repo initialization
   ```

5. **احفظ وأغلق المحرر**

6. **إذا كنت تريد تغيير رسالة الـ commit النهائي:**
   - سيظهر محرر آخر لرسالة الـ commit
   - عدّل الرسالة واحفظ

7. **إذا كان المشروع مرفوعاً على GitHub:**
   ```bash
   git push origin main --force
   # أو
   git push origin main --force-with-lease  # أكثر أماناً
   ```

## الطريقة 2: Squash All Commits (دمج كل الـ commits في واحد)

### خطوات:

1. **أنشئ orphan branch جديد (بدون تاريخ):**
   ```bash
   git checkout --orphan new-main
   ```

2. **أضف جميع الملفات:**
   ```bash
   git add .
   ```

3. **أنشئ commit واحد جديد:**
   ```bash
   git commit -m "Initial commit: Free ADB Remote"
   ```

4. **احذف branch القديم:**
   ```bash
   git branch -D main
   ```

5. **أعد تسمية branch الجديد:**
   ```bash
   git branch -m main
   ```

6. **ارفع إلى GitHub:**
   ```bash
   git push origin main --force
   ```

## الطريقة 3: Reset ثم Commit جديد (الأبسط)

### خطوات:

1. **احفظ جميع التغييرات:**
   ```bash
   git add .
   git stash
   ```

2. **ارجع إلى commit معين (أو ابدأ من جديد):**
   ```bash
   git reset --hard <commit-hash-you-want-to-keep>
   # أو للبدء من جديد:
   git update-ref -d HEAD
   ```

3. **أعد التغييرات:**
   ```bash
   git stash pop
   ```

4. **أنشئ commit جديد:**
   ```bash
   git add .
   git commit -m "Initial commit: Free ADB Remote"
   ```

5. **ارفع إلى GitHub:**
   ```bash
   git push origin main --force
   ```

## الطريقة 4: استخدام git filter-branch (لحذف commits محددة)

### خطوات:

1. **احذف commits محددة:**
   ```bash
   git filter-branch --index-filter 'git rm --cached --ignore-unmatch <file>' HEAD
   ```

2. **أو احذف commits من نطاق معين:**
   ```bash
   git rebase --onto <commit-before-range> <first-commit-to-remove> <last-commit-to-remove>
   ```

## ⚠️ تحذيرات مهمة:

1. **Force Push خطير:**
   - لا تستخدم `--force` إذا كان هناك collaborators آخرون
   - استخدم `--force-with-lease` بدلاً منه (أكثر أماناً)

2. **احفظ نسخة احتياطية:**
   ```bash
   git branch backup-before-cleanup
   ```

3. **إذا كان المشروع مرفوعاً على GitHub:**
   - Force push سيحذف التاريخ القديم
   - تأكد من أن لا أحد يعمل على المشروع

## مثال عملي: تنظيف المشروع الحالي

إذا كنت تريد الاحتفاظ فقط بـ "Initial commit" ودمج الباقي:

```bash
# 1. أنشئ backup
git branch backup-main

# 2. ابدأ interactive rebase
git rebase -i f6be8d6  # أو HEAD~4

# 3. في المحرر، غيّر:
# pick f6be8d6 Initial commit
# squash 23a72da Add GitHub Actions workflow
# squash 14567b3 Add quick start guide
# squash aa11c46 Add repository setup guide
# squash a63a83e repo initialization

# 4. احفظ وأغلق

# 5. إذا كان مرفوعاً:
git push origin main --force-with-lease
```

## الطريقة الموصى بها للمشروع الجديد:

إذا كان المشروع جديداً ولم يرفع بعد، استخدم **الطريقة 2 (Orphan Branch)**:

```bash
git checkout --orphan clean-main
git add .
git commit -m "Initial commit: Free ADB Remote - Complete application"
git branch -D main
git branch -m main
git push origin main --force
```

هذا سيعطيك تاريخ نظيف مع commit واحد فقط!

