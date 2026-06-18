"""
Hermes Android Skills Engine — simplified skill discovery/loading/creation.

Simplified from the full Hermes skills system (tools/skills_tool.py 1638 lines +
agent/skill_commands.py 612 lines + tools/skills_hub.py 3888 lines) into a single
~500 line module focused on Android MVP needs.

Skills are Markdown files with YAML frontmatter stored in a skills/ directory.
The agent can list, view, activate, and create skills.
"""

import json
import os
import re
import time
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────────────
_skills_dir: Optional[str] = None
_initialized = False


def initialize(skills_dir: str = None) -> dict:
    """Initialize the skills engine.
    
    Args:
        skills_dir: Path to skills directory. If None, uses app's internal storage.
    
    Returns:
        dict with status info
    """
    global _skills_dir, _initialized
    
    if _initialized:
        return {"status": "already_initialized", "dir": _skills_dir}
    
    _skills_dir = skills_dir or os.path.join(
        os.environ.get("HOME", "/data"), "hermes_skills"
    )
    
    # Ensure directory exists
    Path(_skills_dir).mkdir(parents=True, exist_ok=True)
    
    # Create example skill if empty
    if not any(Path(_skills_dir).iterdir()):
        _create_example_skills()
    
    _initialized = True
    skills = list_skills()
    
    return {
        "status": "initialized",
        "dir": _skills_dir,
        "count": len(skills),
    }


# ── Frontmatter parsing ───────────────────────────────────────────────────

_FRONTMATTER_RE = re.compile(r'^---\s*\n(.*?)\n---\s*\n', re.DOTALL)


def _parse_frontmatter(content: str) -> Tuple[Dict[str, Any], str]:
    """Parse YAML frontmatter from Markdown content.
    
    Returns:
        (frontmatter_dict, body_text)
    """
    match = _FRONTMATTER_RE.match(content)
    if not match:
        return {}, content
    
    frontmatter_text = match.group(1)
    body = content[match.end():]
    
    # Simple YAML parser (no PyYAML dependency)
    frontmatter = {}
    current_key = None
    current_value = []
    
    for line in frontmatter_text.split('\n'):
        line = line.rstrip()
        
        # Key-value pair
        if ':' in line and not line.startswith(' '):
            # Save previous key
            if current_key:
                frontmatter[current_key] = _parse_yaml_value('\n'.join(current_value))
            
            # Start new key
            key, _, value = line.partition(':')
            current_key = key.strip()
            current_value = [value.strip()] if value.strip() else []
        
        # Continuation or list item
        elif current_key:
            current_value.append(line)
    
    # Save last key
    if current_key:
        frontmatter[current_key] = _parse_yaml_value('\n'.join(current_value))
    
    return frontmatter, body


def _parse_yaml_value(text: str) -> Any:
    """Parse a simple YAML value (string, list, number, boolean)."""
    text = text.strip()
    
    if not text:
        return ""
    
    # List: [item1, item2]
    if text.startswith('[') and text.endswith(']'):
        items = text[1:-1].split(',')
        return [item.strip().strip('"').strip("'") for item in items if item.strip()]
    
    # Boolean
    if text.lower() in ('true', 'yes'):
        return True
    if text.lower() in ('false', 'no'):
        return False
    
    # Number
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        pass
    
    # String (remove quotes)
    return text.strip('"').strip("'")


# ── Skill discovery ───────────────────────────────────────────────────────

def _find_skill_files() -> List[Path]:
    """Find all SKILL.md files in the skills directory."""
    if not _skills_dir or not Path(_skills_dir).exists():
        return []
    
    return list(Path(_skills_dir).rglob("SKILL.md"))


def _parse_skill_file(skill_path: Path) -> Optional[Dict[str, Any]]:
    """Parse a SKILL.md file into a skill metadata dict."""
    try:
        content = skill_path.read_text(encoding='utf-8')
        frontmatter, body = _parse_frontmatter(content)
        
        # Extract metadata
        name = frontmatter.get('name', skill_path.parent.name)
        description = frontmatter.get('description', '')
        
        if not description:
            # Try to extract from first non-heading line
            for line in body.strip().split('\n'):
                line = line.strip()
                if line and not line.startswith('#'):
                    description = line[:200]
                    break
        
        # Category from parent directory
        category = None
        rel_path = skill_path.relative_to(_skills_dir)
        parts = rel_path.parts
        if len(parts) >= 2:
            category = parts[0]
        
        return {
            'name': name,
            'description': description,
            'category': category,
            'version': frontmatter.get('version', '1.0.0'),
            'tags': frontmatter.get('tags', []),
            'platforms': frontmatter.get('platforms', []),
            'path': str(skill_path),
            'dir': str(skill_path.parent),
            'size': len(content),
            'has_references': (skill_path.parent / 'references').is_dir(),
            'has_templates': (skill_path.parent / 'templates').is_dir(),
        }
    except Exception as e:
        logger.warning("Failed to parse skill %s: %s", skill_path, e)
        return None


# ── Public API ─────────────────────────────────────────────────────────────

def list_skills(category: str = None) -> List[Dict[str, Any]]:
    """List all available skills.
    
    Args:
        category: Optional category filter
    
    Returns:
        List of skill metadata dicts
    """
    skills = []
    
    for skill_path in _find_skill_files():
        skill = _parse_skill_file(skill_path)
        if skill:
            if category and skill.get('category') != category:
                continue
            skills.append(skill)
    
    # Sort by category then name
    skills.sort(key=lambda s: (s.get('category') or '', s['name']))
    return skills


def get_categories() -> List[str]:
    """Get all unique skill categories."""
    skills = list_skills()
    return sorted({s['category'] for s in skills if s.get('category')})


def view_skill(name: str) -> Optional[Dict[str, Any]]:
    """View a skill's full content.
    
    Args:
        name: Skill name (case-insensitive)
    
    Returns:
        dict with skill metadata + full content, or None if not found
    """
    # Find skill by name
    for skill_path in _find_skill_files():
        skill = _parse_skill_file(skill_path)
        if skill and skill['name'].lower() == name.lower():
            # Load full content
            try:
                content = skill_path.read_text(encoding='utf-8')
                frontmatter, body = _parse_frontmatter(content)
                
                skill['frontmatter'] = frontmatter
                skill['body'] = body
                skill['full_content'] = content
                
                # Load references if available
                refs_dir = Path(skill['dir']) / 'references'
                if refs_dir.is_dir():
                    skill['references'] = {}
                    for ref_file in refs_dir.glob('*.md'):
                        try:
                            skill['references'][ref_file.name] = ref_file.read_text(encoding='utf-8')
                        except:
                            pass
                
                return skill
            except Exception as e:
                logger.warning("Failed to load skill %s: %s", name, e)
                return None
    
    return None


def activate_skill(name: str) -> Optional[str]:
    """Activate a skill and return its content for injection into agent context.
    
    Args:
        name: Skill name
    
    Returns:
        Skill content string for system prompt injection, or None if not found
    """
    skill = view_skill(name)
    if not skill:
        return None
    
    # Build activation content
    parts = []
    parts.append(f"[Skill: {skill['name']}]")
    if skill.get('description'):
        parts.append(f"Description: {skill['description']}")
    parts.append("")
    parts.append(skill.get('body', ''))
    
    return '\n'.join(parts)


def create_skill(
    name: str,
    description: str,
    content: str,
    category: str = None,
    tags: List[str] = None,
    version: str = "1.0.0",
) -> Dict[str, Any]:
    """Create a new skill.
    
    Args:
        name: Skill name (will be slugified)
        description: Brief description
        content: Full skill content (Markdown body)
        category: Optional category (subdirectory)
        tags: Optional list of tags
        version: Version string
    
    Returns:
        dict with created skill info
    """
    # Slugify name
    slug = re.sub(r'[^a-z0-9-]', '-', name.lower())
    slug = re.sub(r'-+', '-', slug).strip('-')
    
    # Build directory path
    if category:
        cat_slug = re.sub(r'[^a-z0-9-]', '-', category.lower())
        skill_dir = Path(_skills_dir) / cat_slug / slug
    else:
        skill_dir = Path(_skills_dir) / slug
    
    skill_dir.mkdir(parents=True, exist_ok=True)
    
    # Build SKILL.md content
    frontmatter_lines = ['---']
    frontmatter_lines.append(f'name: {name}')
    frontmatter_lines.append(f'description: {description}')
    frontmatter_lines.append(f'version: {version}')
    if tags:
        frontmatter_lines.append(f'tags: [{", ".join(tags)}]')
    frontmatter_lines.append(f'created: {time.strftime("%Y-%m-%d")}')
    frontmatter_lines.append('---')
    frontmatter_lines.append('')
    frontmatter_lines.append(content)
    
    skill_file = skill_dir / 'SKILL.md'
    skill_file.write_text('\n'.join(frontmatter_lines), encoding='utf-8')
    
    # Parse and return the created skill
    skill = _parse_skill_file(skill_file)
    return skill or {
        'name': name,
        'description': description,
        'category': category,
        'path': str(skill_file),
    }


def delete_skill(name: str) -> bool:
    """Delete a skill.
    
    Args:
        name: Skill name
    
    Returns:
        True if deleted, False if not found
    """
    import shutil
    
    for skill_path in _find_skill_files():
        skill = _parse_skill_file(skill_path)
        if skill and skill['name'].lower() == name.lower():
            skill_dir = skill_path.parent
            shutil.rmtree(skill_dir)
            return True
    
    return False


def search_skills(query: str) -> List[Dict[str, Any]]:
    """Search skills by name, description, or tags.
    
    Args:
        query: Search text
    
    Returns:
        List of matching skills
    """
    query_lower = query.lower()
    results = []
    
    for skill in list_skills():
        # Search in name
        if query_lower in skill['name'].lower():
            results.append(skill)
            continue
        
        # Search in description
        if query_lower in skill.get('description', '').lower():
            results.append(skill)
            continue
        
        # Search in tags
        if any(query_lower in tag.lower() for tag in skill.get('tags', [])):
            results.append(skill)
            continue
        
        # Search in category
        if query_lower in (skill.get('category') or '').lower():
            results.append(skill)
            continue
    
    return results


# ── Example skills creation ────────────────────────────────────────────────

def _create_example_skills():
    """Create example skills for demonstration."""
    examples = [
        {
            'name': '代码审查助手',
            'description': '帮助审查代码质量、逻辑漏洞、性能问题',
            'category': 'coding',
            'tags': ['code', 'review', 'quality'],
            'content': '''# 代码审查助手

你是一个专业的代码审查助手。当用户分享代码时，请从以下维度进行审查：

## 审查维度

1. **逻辑正确性** — 代码是否实现了预期功能
2. **错误处理** — 异常和边界条件是否处理
3. **性能** — 是否有明显的性能问题
4. **安全性** — 是否存在安全漏洞
5. **可维护性** — 代码结构、命名、注释是否清晰

## 输出格式

```
## 审查结果

### 优点
- ...

### 问题
- [严重] ...
- [建议] ...

### 改进建议
1. ...
```
'''
        },
        {
            'name': 'Shell 命令助手',
            'description': '帮助构建和解释 Shell 命令',
            'category': 'system',
            'tags': ['shell', 'bash', 'command'],
            'content': '''# Shell 命令助手

你是一个 Shell 命令专家。帮助用户：
- 构建复杂的 shell 命令
- 解释现有命令的作用
- 调试命令错误
- 提供安全的替代方案

## 注意事项
- 优先使用安全的命令（避免 rm -rf 等危险操作）
- 解释每个参数的作用
- 提供 dry-run 方案供用户确认
'''
        },
        {
            'name': '学习伙伴',
            'description': '帮助学习新知识，提供解释和练习',
            'category': 'education',
            'tags': ['learning', 'study', 'education'],
            'content': '''# 学习伙伴

你是一个耐心的学习伙伴。帮助用户：
- 理解复杂概念
- 提供实例和类比
- 设计练习题
- 检查理解程度

## 教学方法
1. 先了解用户的基础
2. 从简单到复杂逐步讲解
3. 用实际例子说明
4. 提供动手练习
5. 定期检查理解
'''
        },
    ]
    
    for example in examples:
        try:
            create_skill(
                name=example['name'],
                description=example['description'],
                content=example['content'],
                category=example.get('category'),
                tags=example.get('tags'),
            )
        except Exception as e:
            logger.warning("Failed to create example skill: %s", e)


# ── Statistics ─────────────────────────────────────────────────────────────

def get_stats() -> dict:
    """Get skills statistics."""
    skills = list_skills()
    categories = get_categories()
    
    return {
        'total_skills': len(skills),
        'categories': len(categories),
        'category_list': categories,
        'skills_dir': _skills_dir,
    }