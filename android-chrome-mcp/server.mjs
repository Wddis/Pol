#!/usr/bin/env node
import{createRequire as __cr}from"node:module";const require=__cr(import.meta.url);
var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf, __hasOwnProp = Object.prototype.hasOwnProperty;
var __commonJS = (cb, mod) => function() {
  return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
};
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: !0 });
}, __copyProps = (to, from, except, desc) => {
  if (from && typeof from == "object" || typeof from == "function")
    for (let key of __getOwnPropNames(from))
      !__hasOwnProp.call(to, key) && key !== except && __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: !0 }) : target,
  mod
));

// node_modules/ajv/dist/compile/codegen/code.js
var require_code = __commonJS({
  "node_modules/ajv/dist/compile/codegen/code.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.regexpCode = exports.getEsmExportName = exports.getProperty = exports.safeStringify = exports.stringify = exports.strConcat = exports.addCodeArg = exports.str = exports._ = exports.nil = exports._Code = exports.Name = exports.IDENTIFIER = exports._CodeOrName = void 0;
    var _CodeOrName = class {
    };
    exports._CodeOrName = _CodeOrName;
    exports.IDENTIFIER = /^[a-z$_][a-z$_0-9]*$/i;
    var Name = class extends _CodeOrName {
      constructor(s) {
        if (super(), !exports.IDENTIFIER.test(s))
          throw new Error("CodeGen: name must be a valid identifier");
        this.str = s;
      }
      toString() {
        return this.str;
      }
      emptyStr() {
        return !1;
      }
      get names() {
        return { [this.str]: 1 };
      }
    };
    exports.Name = Name;
    var _Code = class extends _CodeOrName {
      constructor(code) {
        super(), this._items = typeof code == "string" ? [code] : code;
      }
      toString() {
        return this.str;
      }
      emptyStr() {
        if (this._items.length > 1)
          return !1;
        let item = this._items[0];
        return item === "" || item === '""';
      }
      get str() {
        var _a;
        return (_a = this._str) !== null && _a !== void 0 ? _a : this._str = this._items.reduce((s, c) => `${s}${c}`, "");
      }
      get names() {
        var _a;
        return (_a = this._names) !== null && _a !== void 0 ? _a : this._names = this._items.reduce((names, c) => (c instanceof Name && (names[c.str] = (names[c.str] || 0) + 1), names), {});
      }
    };
    exports._Code = _Code;
    exports.nil = new _Code("");
    function _(strs, ...args) {
      let code = [strs[0]], i = 0;
      for (; i < args.length; )
        addCodeArg(code, args[i]), code.push(strs[++i]);
      return new _Code(code);
    }
    exports._ = _;
    var plus = new _Code("+");
    function str(strs, ...args) {
      let expr = [safeStringify(strs[0])], i = 0;
      for (; i < args.length; )
        expr.push(plus), addCodeArg(expr, args[i]), expr.push(plus, safeStringify(strs[++i]));
      return optimize(expr), new _Code(expr);
    }
    exports.str = str;
    function addCodeArg(code, arg) {
      arg instanceof _Code ? code.push(...arg._items) : arg instanceof Name ? code.push(arg) : code.push(interpolate(arg));
    }
    exports.addCodeArg = addCodeArg;
    function optimize(expr) {
      let i = 1;
      for (; i < expr.length - 1; ) {
        if (expr[i] === plus) {
          let res = mergeExprItems(expr[i - 1], expr[i + 1]);
          if (res !== void 0) {
            expr.splice(i - 1, 3, res);
            continue;
          }
          expr[i++] = "+";
        }
        i++;
      }
    }
    function mergeExprItems(a, b) {
      if (b === '""')
        return a;
      if (a === '""')
        return b;
      if (typeof a == "string")
        return b instanceof Name || a[a.length - 1] !== '"' ? void 0 : typeof b != "string" ? `${a.slice(0, -1)}${b}"` : b[0] === '"' ? a.slice(0, -1) + b.slice(1) : void 0;
      if (typeof b == "string" && b[0] === '"' && !(a instanceof Name))
        return `"${a}${b.slice(1)}`;
    }
    function strConcat(c1, c2) {
      return c2.emptyStr() ? c1 : c1.emptyStr() ? c2 : str`${c1}${c2}`;
    }
    exports.strConcat = strConcat;
    function interpolate(x) {
      return typeof x == "number" || typeof x == "boolean" || x === null ? x : safeStringify(Array.isArray(x) ? x.join(",") : x);
    }
    function stringify(x) {
      return new _Code(safeStringify(x));
    }
    exports.stringify = stringify;
    function safeStringify(x) {
      return JSON.stringify(x).replace(/\u2028/g, "\\u2028").replace(/\u2029/g, "\\u2029");
    }
    exports.safeStringify = safeStringify;
    function getProperty(key) {
      return typeof key == "string" && exports.IDENTIFIER.test(key) ? new _Code(`.${key}`) : _`[${key}]`;
    }
    exports.getProperty = getProperty;
    function getEsmExportName(key) {
      if (typeof key == "string" && exports.IDENTIFIER.test(key))
        return new _Code(`${key}`);
      throw new Error(`CodeGen: invalid export name: ${key}, use explicit $id name mapping`);
    }
    exports.getEsmExportName = getEsmExportName;
    function regexpCode(rx) {
      return new _Code(rx.toString());
    }
    exports.regexpCode = regexpCode;
  }
});

// node_modules/ajv/dist/compile/codegen/scope.js
var require_scope = __commonJS({
  "node_modules/ajv/dist/compile/codegen/scope.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.ValueScope = exports.ValueScopeName = exports.Scope = exports.varKinds = exports.UsedValueState = void 0;
    var code_1 = require_code(), ValueError = class extends Error {
      constructor(name) {
        super(`CodeGen: "code" for ${name} not defined`), this.value = name.value;
      }
    }, UsedValueState;
    (function(UsedValueState2) {
      UsedValueState2[UsedValueState2.Started = 0] = "Started", UsedValueState2[UsedValueState2.Completed = 1] = "Completed";
    })(UsedValueState || (exports.UsedValueState = UsedValueState = {}));
    exports.varKinds = {
      const: new code_1.Name("const"),
      let: new code_1.Name("let"),
      var: new code_1.Name("var")
    };
    var Scope = class {
      constructor({ prefixes, parent } = {}) {
        this._names = {}, this._prefixes = prefixes, this._parent = parent;
      }
      toName(nameOrPrefix) {
        return nameOrPrefix instanceof code_1.Name ? nameOrPrefix : this.name(nameOrPrefix);
      }
      name(prefix) {
        return new code_1.Name(this._newName(prefix));
      }
      _newName(prefix) {
        let ng = this._names[prefix] || this._nameGroup(prefix);
        return `${prefix}${ng.index++}`;
      }
      _nameGroup(prefix) {
        var _a, _b;
        if (!((_b = (_a = this._parent) === null || _a === void 0 ? void 0 : _a._prefixes) === null || _b === void 0) && _b.has(prefix) || this._prefixes && !this._prefixes.has(prefix))
          throw new Error(`CodeGen: prefix "${prefix}" is not allowed in this scope`);
        return this._names[prefix] = { prefix, index: 0 };
      }
    };
    exports.Scope = Scope;
    var ValueScopeName = class extends code_1.Name {
      constructor(prefix, nameStr) {
        super(nameStr), this.prefix = prefix;
      }
      setValue(value, { property, itemIndex }) {
        this.value = value, this.scopePath = (0, code_1._)`.${new code_1.Name(property)}[${itemIndex}]`;
      }
    };
    exports.ValueScopeName = ValueScopeName;
    var line = (0, code_1._)`\n`, ValueScope = class extends Scope {
      constructor(opts) {
        super(opts), this._values = {}, this._scope = opts.scope, this.opts = { ...opts, _n: opts.lines ? line : code_1.nil };
      }
      get() {
        return this._scope;
      }
      name(prefix) {
        return new ValueScopeName(prefix, this._newName(prefix));
      }
      value(nameOrPrefix, value) {
        var _a;
        if (value.ref === void 0)
          throw new Error("CodeGen: ref must be passed in value");
        let name = this.toName(nameOrPrefix), { prefix } = name, valueKey = (_a = value.key) !== null && _a !== void 0 ? _a : value.ref, vs = this._values[prefix];
        if (vs) {
          let _name = vs.get(valueKey);
          if (_name)
            return _name;
        } else
          vs = this._values[prefix] = /* @__PURE__ */ new Map();
        vs.set(valueKey, name);
        let s = this._scope[prefix] || (this._scope[prefix] = []), itemIndex = s.length;
        return s[itemIndex] = value.ref, name.setValue(value, { property: prefix, itemIndex }), name;
      }
      getValue(prefix, keyOrRef) {
        let vs = this._values[prefix];
        if (vs)
          return vs.get(keyOrRef);
      }
      scopeRefs(scopeName, values = this._values) {
        return this._reduceValues(values, (name) => {
          if (name.scopePath === void 0)
            throw new Error(`CodeGen: name "${name}" has no value`);
          return (0, code_1._)`${scopeName}${name.scopePath}`;
        });
      }
      scopeCode(values = this._values, usedValues, getCode) {
        return this._reduceValues(values, (name) => {
          if (name.value === void 0)
            throw new Error(`CodeGen: name "${name}" has no value`);
          return name.value.code;
        }, usedValues, getCode);
      }
      _reduceValues(values, valueCode, usedValues = {}, getCode) {
        let code = code_1.nil;
        for (let prefix in values) {
          let vs = values[prefix];
          if (!vs)
            continue;
          let nameSet = usedValues[prefix] = usedValues[prefix] || /* @__PURE__ */ new Map();
          vs.forEach((name) => {
            if (nameSet.has(name))
              return;
            nameSet.set(name, UsedValueState.Started);
            let c = valueCode(name);
            if (c) {
              let def = this.opts.es5 ? exports.varKinds.var : exports.varKinds.const;
              code = (0, code_1._)`${code}${def} ${name} = ${c};${this.opts._n}`;
            } else if (c = getCode?.(name))
              code = (0, code_1._)`${code}${c}${this.opts._n}`;
            else
              throw new ValueError(name);
            nameSet.set(name, UsedValueState.Completed);
          });
        }
        return code;
      }
    };
    exports.ValueScope = ValueScope;
  }
});

// node_modules/ajv/dist/compile/codegen/index.js
var require_codegen = __commonJS({
  "node_modules/ajv/dist/compile/codegen/index.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.or = exports.and = exports.not = exports.CodeGen = exports.operators = exports.varKinds = exports.ValueScopeName = exports.ValueScope = exports.Scope = exports.Name = exports.regexpCode = exports.stringify = exports.getProperty = exports.nil = exports.strConcat = exports.str = exports._ = void 0;
    var code_1 = require_code(), scope_1 = require_scope(), code_2 = require_code();
    Object.defineProperty(exports, "_", { enumerable: !0, get: function() {
      return code_2._;
    } });
    Object.defineProperty(exports, "str", { enumerable: !0, get: function() {
      return code_2.str;
    } });
    Object.defineProperty(exports, "strConcat", { enumerable: !0, get: function() {
      return code_2.strConcat;
    } });
    Object.defineProperty(exports, "nil", { enumerable: !0, get: function() {
      return code_2.nil;
    } });
    Object.defineProperty(exports, "getProperty", { enumerable: !0, get: function() {
      return code_2.getProperty;
    } });
    Object.defineProperty(exports, "stringify", { enumerable: !0, get: function() {
      return code_2.stringify;
    } });
    Object.defineProperty(exports, "regexpCode", { enumerable: !0, get: function() {
      return code_2.regexpCode;
    } });
    Object.defineProperty(exports, "Name", { enumerable: !0, get: function() {
      return code_2.Name;
    } });
    var scope_2 = require_scope();
    Object.defineProperty(exports, "Scope", { enumerable: !0, get: function() {
      return scope_2.Scope;
    } });
    Object.defineProperty(exports, "ValueScope", { enumerable: !0, get: function() {
      return scope_2.ValueScope;
    } });
    Object.defineProperty(exports, "ValueScopeName", { enumerable: !0, get: function() {
      return scope_2.ValueScopeName;
    } });
    Object.defineProperty(exports, "varKinds", { enumerable: !0, get: function() {
      return scope_2.varKinds;
    } });
    exports.operators = {
      GT: new code_1._Code(">"),
      GTE: new code_1._Code(">="),
      LT: new code_1._Code("<"),
      LTE: new code_1._Code("<="),
      EQ: new code_1._Code("==="),
      NEQ: new code_1._Code("!=="),
      NOT: new code_1._Code("!"),
      OR: new code_1._Code("||"),
      AND: new code_1._Code("&&"),
      ADD: new code_1._Code("+")
    };
    var Node = class {
      optimizeNodes() {
        return this;
      }
      optimizeNames(_names, _constants) {
        return this;
      }
    }, Def = class extends Node {
      constructor(varKind, name, rhs) {
        super(), this.varKind = varKind, this.name = name, this.rhs = rhs;
      }
      render({ es5, _n }) {
        let varKind = es5 ? scope_1.varKinds.var : this.varKind, rhs = this.rhs === void 0 ? "" : ` = ${this.rhs}`;
        return `${varKind} ${this.name}${rhs};` + _n;
      }
      optimizeNames(names, constants2) {
        if (names[this.name.str])
          return this.rhs && (this.rhs = optimizeExpr(this.rhs, names, constants2)), this;
      }
      get names() {
        return this.rhs instanceof code_1._CodeOrName ? this.rhs.names : {};
      }
    }, Assign = class extends Node {
      constructor(lhs, rhs, sideEffects) {
        super(), this.lhs = lhs, this.rhs = rhs, this.sideEffects = sideEffects;
      }
      render({ _n }) {
        return `${this.lhs} = ${this.rhs};` + _n;
      }
      optimizeNames(names, constants2) {
        if (!(this.lhs instanceof code_1.Name && !names[this.lhs.str] && !this.sideEffects))
          return this.rhs = optimizeExpr(this.rhs, names, constants2), this;
      }
      get names() {
        let names = this.lhs instanceof code_1.Name ? {} : { ...this.lhs.names };
        return addExprNames(names, this.rhs);
      }
    }, AssignOp = class extends Assign {
      constructor(lhs, op, rhs, sideEffects) {
        super(lhs, rhs, sideEffects), this.op = op;
      }
      render({ _n }) {
        return `${this.lhs} ${this.op}= ${this.rhs};` + _n;
      }
    }, Label = class extends Node {
      constructor(label) {
        super(), this.label = label, this.names = {};
      }
      render({ _n }) {
        return `${this.label}:` + _n;
      }
    }, Break = class extends Node {
      constructor(label) {
        super(), this.label = label, this.names = {};
      }
      render({ _n }) {
        return `break${this.label ? ` ${this.label}` : ""};` + _n;
      }
    }, Throw = class extends Node {
      constructor(error2) {
        super(), this.error = error2;
      }
      render({ _n }) {
        return `throw ${this.error};` + _n;
      }
      get names() {
        return this.error.names;
      }
    }, AnyCode = class extends Node {
      constructor(code) {
        super(), this.code = code;
      }
      render({ _n }) {
        return `${this.code};` + _n;
      }
      optimizeNodes() {
        return `${this.code}` ? this : void 0;
      }
      optimizeNames(names, constants2) {
        return this.code = optimizeExpr(this.code, names, constants2), this;
      }
      get names() {
        return this.code instanceof code_1._CodeOrName ? this.code.names : {};
      }
    }, ParentNode = class extends Node {
      constructor(nodes = []) {
        super(), this.nodes = nodes;
      }
      render(opts) {
        return this.nodes.reduce((code, n) => code + n.render(opts), "");
      }
      optimizeNodes() {
        let { nodes } = this, i = nodes.length;
        for (; i--; ) {
          let n = nodes[i].optimizeNodes();
          Array.isArray(n) ? nodes.splice(i, 1, ...n) : n ? nodes[i] = n : nodes.splice(i, 1);
        }
        return nodes.length > 0 ? this : void 0;
      }
      optimizeNames(names, constants2) {
        let { nodes } = this, i = nodes.length;
        for (; i--; ) {
          let n = nodes[i];
          n.optimizeNames(names, constants2) || (subtractNames(names, n.names), nodes.splice(i, 1));
        }
        return nodes.length > 0 ? this : void 0;
      }
      get names() {
        return this.nodes.reduce((names, n) => addNames(names, n.names), {});
      }
    }, BlockNode = class extends ParentNode {
      render(opts) {
        return "{" + opts._n + super.render(opts) + "}" + opts._n;
      }
    }, Root = class extends ParentNode {
    }, Else = class extends BlockNode {
    };
    Else.kind = "else";
    var If = class _If extends BlockNode {
      constructor(condition, nodes) {
        super(nodes), this.condition = condition;
      }
      render(opts) {
        let code = `if(${this.condition})` + super.render(opts);
        return this.else && (code += "else " + this.else.render(opts)), code;
      }
      optimizeNodes() {
        super.optimizeNodes();
        let cond = this.condition;
        if (cond === !0)
          return this.nodes;
        let e = this.else;
        if (e) {
          let ns = e.optimizeNodes();
          e = this.else = Array.isArray(ns) ? new Else(ns) : ns;
        }
        if (e)
          return cond === !1 ? e instanceof _If ? e : e.nodes : this.nodes.length ? this : new _If(not(cond), e instanceof _If ? [e] : e.nodes);
        if (!(cond === !1 || !this.nodes.length))
          return this;
      }
      optimizeNames(names, constants2) {
        var _a;
        if (this.else = (_a = this.else) === null || _a === void 0 ? void 0 : _a.optimizeNames(names, constants2), !!(super.optimizeNames(names, constants2) || this.else))
          return this.condition = optimizeExpr(this.condition, names, constants2), this;
      }
      get names() {
        let names = super.names;
        return addExprNames(names, this.condition), this.else && addNames(names, this.else.names), names;
      }
    };
    If.kind = "if";
    var For = class extends BlockNode {
    };
    For.kind = "for";
    var ForLoop = class extends For {
      constructor(iteration) {
        super(), this.iteration = iteration;
      }
      render(opts) {
        return `for(${this.iteration})` + super.render(opts);
      }
      optimizeNames(names, constants2) {
        if (super.optimizeNames(names, constants2))
          return this.iteration = optimizeExpr(this.iteration, names, constants2), this;
      }
      get names() {
        return addNames(super.names, this.iteration.names);
      }
    }, ForRange = class extends For {
      constructor(varKind, name, from, to) {
        super(), this.varKind = varKind, this.name = name, this.from = from, this.to = to;
      }
      render(opts) {
        let varKind = opts.es5 ? scope_1.varKinds.var : this.varKind, { name, from, to } = this;
        return `for(${varKind} ${name}=${from}; ${name}<${to}; ${name}++)` + super.render(opts);
      }
      get names() {
        let names = addExprNames(super.names, this.from);
        return addExprNames(names, this.to);
      }
    }, ForIter = class extends For {
      constructor(loop, varKind, name, iterable) {
        super(), this.loop = loop, this.varKind = varKind, this.name = name, this.iterable = iterable;
      }
      render(opts) {
        return `for(${this.varKind} ${this.name} ${this.loop} ${this.iterable})` + super.render(opts);
      }
      optimizeNames(names, constants2) {
        if (super.optimizeNames(names, constants2))
          return this.iterable = optimizeExpr(this.iterable, names, constants2), this;
      }
      get names() {
        return addNames(super.names, this.iterable.names);
      }
    }, Func = class extends BlockNode {
      constructor(name, args, async) {
        super(), this.name = name, this.args = args, this.async = async;
      }
      render(opts) {
        return `${this.async ? "async " : ""}function ${this.name}(${this.args})` + super.render(opts);
      }
    };
    Func.kind = "func";
    var Return = class extends ParentNode {
      render(opts) {
        return "return " + super.render(opts);
      }
    };
    Return.kind = "return";
    var Try = class extends BlockNode {
      render(opts) {
        let code = "try" + super.render(opts);
        return this.catch && (code += this.catch.render(opts)), this.finally && (code += this.finally.render(opts)), code;
      }
      optimizeNodes() {
        var _a, _b;
        return super.optimizeNodes(), (_a = this.catch) === null || _a === void 0 || _a.optimizeNodes(), (_b = this.finally) === null || _b === void 0 || _b.optimizeNodes(), this;
      }
      optimizeNames(names, constants2) {
        var _a, _b;
        return super.optimizeNames(names, constants2), (_a = this.catch) === null || _a === void 0 || _a.optimizeNames(names, constants2), (_b = this.finally) === null || _b === void 0 || _b.optimizeNames(names, constants2), this;
      }
      get names() {
        let names = super.names;
        return this.catch && addNames(names, this.catch.names), this.finally && addNames(names, this.finally.names), names;
      }
    }, Catch = class extends BlockNode {
      constructor(error2) {
        super(), this.error = error2;
      }
      render(opts) {
        return `catch(${this.error})` + super.render(opts);
      }
    };
    Catch.kind = "catch";
    var Finally = class extends BlockNode {
      render(opts) {
        return "finally" + super.render(opts);
      }
    };
    Finally.kind = "finally";
    var CodeGen = class {
      constructor(extScope, opts = {}) {
        this._values = {}, this._blockStarts = [], this._constants = {}, this.opts = { ...opts, _n: opts.lines ? `
` : "" }, this._extScope = extScope, this._scope = new scope_1.Scope({ parent: extScope }), this._nodes = [new Root()];
      }
      toString() {
        return this._root.render(this.opts);
      }
      // returns unique name in the internal scope
      name(prefix) {
        return this._scope.name(prefix);
      }
      // reserves unique name in the external scope
      scopeName(prefix) {
        return this._extScope.name(prefix);
      }
      // reserves unique name in the external scope and assigns value to it
      scopeValue(prefixOrName, value) {
        let name = this._extScope.value(prefixOrName, value);
        return (this._values[name.prefix] || (this._values[name.prefix] = /* @__PURE__ */ new Set())).add(name), name;
      }
      getScopeValue(prefix, keyOrRef) {
        return this._extScope.getValue(prefix, keyOrRef);
      }
      // return code that assigns values in the external scope to the names that are used internally
      // (same names that were returned by gen.scopeName or gen.scopeValue)
      scopeRefs(scopeName) {
        return this._extScope.scopeRefs(scopeName, this._values);
      }
      scopeCode() {
        return this._extScope.scopeCode(this._values);
      }
      _def(varKind, nameOrPrefix, rhs, constant) {
        let name = this._scope.toName(nameOrPrefix);
        return rhs !== void 0 && constant && (this._constants[name.str] = rhs), this._leafNode(new Def(varKind, name, rhs)), name;
      }
      // `const` declaration (`var` in es5 mode)
      const(nameOrPrefix, rhs, _constant) {
        return this._def(scope_1.varKinds.const, nameOrPrefix, rhs, _constant);
      }
      // `let` declaration with optional assignment (`var` in es5 mode)
      let(nameOrPrefix, rhs, _constant) {
        return this._def(scope_1.varKinds.let, nameOrPrefix, rhs, _constant);
      }
      // `var` declaration with optional assignment
      var(nameOrPrefix, rhs, _constant) {
        return this._def(scope_1.varKinds.var, nameOrPrefix, rhs, _constant);
      }
      // assignment code
      assign(lhs, rhs, sideEffects) {
        return this._leafNode(new Assign(lhs, rhs, sideEffects));
      }
      // `+=` code
      add(lhs, rhs) {
        return this._leafNode(new AssignOp(lhs, exports.operators.ADD, rhs));
      }
      // appends passed SafeExpr to code or executes Block
      code(c) {
        return typeof c == "function" ? c() : c !== code_1.nil && this._leafNode(new AnyCode(c)), this;
      }
      // returns code for object literal for the passed argument list of key-value pairs
      object(...keyValues) {
        let code = ["{"];
        for (let [key, value] of keyValues)
          code.length > 1 && code.push(","), code.push(key), (key !== value || this.opts.es5) && (code.push(":"), (0, code_1.addCodeArg)(code, value));
        return code.push("}"), new code_1._Code(code);
      }
      // `if` clause (or statement if `thenBody` and, optionally, `elseBody` are passed)
      if(condition, thenBody, elseBody) {
        if (this._blockNode(new If(condition)), thenBody && elseBody)
          this.code(thenBody).else().code(elseBody).endIf();
        else if (thenBody)
          this.code(thenBody).endIf();
        else if (elseBody)
          throw new Error('CodeGen: "else" body without "then" body');
        return this;
      }
      // `else if` clause - invalid without `if` or after `else` clauses
      elseIf(condition) {
        return this._elseNode(new If(condition));
      }
      // `else` clause - only valid after `if` or `else if` clauses
      else() {
        return this._elseNode(new Else());
      }
      // end `if` statement (needed if gen.if was used only with condition)
      endIf() {
        return this._endBlockNode(If, Else);
      }
      _for(node, forBody) {
        return this._blockNode(node), forBody && this.code(forBody).endFor(), this;
      }
      // a generic `for` clause (or statement if `forBody` is passed)
      for(iteration, forBody) {
        return this._for(new ForLoop(iteration), forBody);
      }
      // `for` statement for a range of values
      forRange(nameOrPrefix, from, to, forBody, varKind = this.opts.es5 ? scope_1.varKinds.var : scope_1.varKinds.let) {
        let name = this._scope.toName(nameOrPrefix);
        return this._for(new ForRange(varKind, name, from, to), () => forBody(name));
      }
      // `for-of` statement (in es5 mode replace with a normal for loop)
      forOf(nameOrPrefix, iterable, forBody, varKind = scope_1.varKinds.const) {
        let name = this._scope.toName(nameOrPrefix);
        if (this.opts.es5) {
          let arr = iterable instanceof code_1.Name ? iterable : this.var("_arr", iterable);
          return this.forRange("_i", 0, (0, code_1._)`${arr}.length`, (i) => {
            this.var(name, (0, code_1._)`${arr}[${i}]`), forBody(name);
          });
        }
        return this._for(new ForIter("of", varKind, name, iterable), () => forBody(name));
      }
      // `for-in` statement.
      // With option `ownProperties` replaced with a `for-of` loop for object keys
      forIn(nameOrPrefix, obj, forBody, varKind = this.opts.es5 ? scope_1.varKinds.var : scope_1.varKinds.const) {
        if (this.opts.ownProperties)
          return this.forOf(nameOrPrefix, (0, code_1._)`Object.keys(${obj})`, forBody);
        let name = this._scope.toName(nameOrPrefix);
        return this._for(new ForIter("in", varKind, name, obj), () => forBody(name));
      }
      // end `for` loop
      endFor() {
        return this._endBlockNode(For);
      }
      // `label` statement
      label(label) {
        return this._leafNode(new Label(label));
      }
      // `break` statement
      break(label) {
        return this._leafNode(new Break(label));
      }
      // `return` statement
      return(value) {
        let node = new Return();
        if (this._blockNode(node), this.code(value), node.nodes.length !== 1)
          throw new Error('CodeGen: "return" should have one node');
        return this._endBlockNode(Return);
      }
      // `try` statement
      try(tryBody, catchCode, finallyCode) {
        if (!catchCode && !finallyCode)
          throw new Error('CodeGen: "try" without "catch" and "finally"');
        let node = new Try();
        if (this._blockNode(node), this.code(tryBody), catchCode) {
          let error2 = this.name("e");
          this._currNode = node.catch = new Catch(error2), catchCode(error2);
        }
        return finallyCode && (this._currNode = node.finally = new Finally(), this.code(finallyCode)), this._endBlockNode(Catch, Finally);
      }
      // `throw` statement
      throw(error2) {
        return this._leafNode(new Throw(error2));
      }
      // start self-balancing block
      block(body, nodeCount) {
        return this._blockStarts.push(this._nodes.length), body && this.code(body).endBlock(nodeCount), this;
      }
      // end the current self-balancing block
      endBlock(nodeCount) {
        let len = this._blockStarts.pop();
        if (len === void 0)
          throw new Error("CodeGen: not in self-balancing block");
        let toClose = this._nodes.length - len;
        if (toClose < 0 || nodeCount !== void 0 && toClose !== nodeCount)
          throw new Error(`CodeGen: wrong number of nodes: ${toClose} vs ${nodeCount} expected`);
        return this._nodes.length = len, this;
      }
      // `function` heading (or definition if funcBody is passed)
      func(name, args = code_1.nil, async, funcBody) {
        return this._blockNode(new Func(name, args, async)), funcBody && this.code(funcBody).endFunc(), this;
      }
      // end function definition
      endFunc() {
        return this._endBlockNode(Func);
      }
      optimize(n = 1) {
        for (; n-- > 0; )
          this._root.optimizeNodes(), this._root.optimizeNames(this._root.names, this._constants);
      }
      _leafNode(node) {
        return this._currNode.nodes.push(node), this;
      }
      _blockNode(node) {
        this._currNode.nodes.push(node), this._nodes.push(node);
      }
      _endBlockNode(N1, N2) {
        let n = this._currNode;
        if (n instanceof N1 || N2 && n instanceof N2)
          return this._nodes.pop(), this;
        throw new Error(`CodeGen: not in block "${N2 ? `${N1.kind}/${N2.kind}` : N1.kind}"`);
      }
      _elseNode(node) {
        let n = this._currNode;
        if (!(n instanceof If))
          throw new Error('CodeGen: "else" without "if"');
        return this._currNode = n.else = node, this;
      }
      get _root() {
        return this._nodes[0];
      }
      get _currNode() {
        let ns = this._nodes;
        return ns[ns.length - 1];
      }
      set _currNode(node) {
        let ns = this._nodes;
        ns[ns.length - 1] = node;
      }
    };
    exports.CodeGen = CodeGen;
    function addNames(names, from) {
      for (let n in from)
        names[n] = (names[n] || 0) + (from[n] || 0);
      return names;
    }
    function addExprNames(names, from) {
      return from instanceof code_1._CodeOrName ? addNames(names, from.names) : names;
    }
    function optimizeExpr(expr, names, constants2) {
      if (expr instanceof code_1.Name)
        return replaceName(expr);
      if (!canOptimize(expr))
        return expr;
      return new code_1._Code(expr._items.reduce((items, c) => (c instanceof code_1.Name && (c = replaceName(c)), c instanceof code_1._Code ? items.push(...c._items) : items.push(c), items), []));
      function replaceName(n) {
        let c = constants2[n.str];
        return c === void 0 || names[n.str] !== 1 ? n : (delete names[n.str], c);
      }
      function canOptimize(e) {
        return e instanceof code_1._Code && e._items.some((c) => c instanceof code_1.Name && names[c.str] === 1 && constants2[c.str] !== void 0);
      }
    }
    function subtractNames(names, from) {
      for (let n in from)
        names[n] = (names[n] || 0) - (from[n] || 0);
    }
    function not(x) {
      return typeof x == "boolean" || typeof x == "number" || x === null ? !x : (0, code_1._)`!${par(x)}`;
    }
    exports.not = not;
    var andCode = mappend(exports.operators.AND);
    function and(...args) {
      return args.reduce(andCode);
    }
    exports.and = and;
    var orCode = mappend(exports.operators.OR);
    function or(...args) {
      return args.reduce(orCode);
    }
    exports.or = or;
    function mappend(op) {
      return (x, y) => x === code_1.nil ? y : y === code_1.nil ? x : (0, code_1._)`${par(x)} ${op} ${par(y)}`;
    }
    function par(x) {
      return x instanceof code_1.Name ? x : (0, code_1._)`(${x})`;
    }
  }
});

// node_modules/ajv/dist/compile/util.js
var require_util = __commonJS({
  "node_modules/ajv/dist/compile/util.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.checkStrictMode = exports.getErrorPath = exports.Type = exports.useFunc = exports.setEvaluated = exports.evaluatedPropsToName = exports.mergeEvaluated = exports.eachItem = exports.unescapeJsonPointer = exports.escapeJsonPointer = exports.escapeFragment = exports.unescapeFragment = exports.schemaRefOrVal = exports.schemaHasRulesButRef = exports.schemaHasRules = exports.checkUnknownRules = exports.alwaysValidSchema = exports.toHash = void 0;
    var codegen_1 = require_codegen(), code_1 = require_code();
    function toHash(arr) {
      let hash = {};
      for (let item of arr)
        hash[item] = !0;
      return hash;
    }
    exports.toHash = toHash;
    function alwaysValidSchema(it, schema) {
      return typeof schema == "boolean" ? schema : Object.keys(schema).length === 0 ? !0 : (checkUnknownRules(it, schema), !schemaHasRules(schema, it.self.RULES.all));
    }
    exports.alwaysValidSchema = alwaysValidSchema;
    function checkUnknownRules(it, schema = it.schema) {
      let { opts, self } = it;
      if (!opts.strictSchema || typeof schema == "boolean")
        return;
      let rules = self.RULES.keywords;
      for (let key in schema)
        rules[key] || checkStrictMode(it, `unknown keyword: "${key}"`);
    }
    exports.checkUnknownRules = checkUnknownRules;
    function schemaHasRules(schema, rules) {
      if (typeof schema == "boolean")
        return !schema;
      for (let key in schema)
        if (rules[key])
          return !0;
      return !1;
    }
    exports.schemaHasRules = schemaHasRules;
    function schemaHasRulesButRef(schema, RULES) {
      if (typeof schema == "boolean")
        return !schema;
      for (let key in schema)
        if (key !== "$ref" && RULES.all[key])
          return !0;
      return !1;
    }
    exports.schemaHasRulesButRef = schemaHasRulesButRef;
    function schemaRefOrVal({ topSchemaRef, schemaPath }, schema, keyword, $data) {
      if (!$data) {
        if (typeof schema == "number" || typeof schema == "boolean")
          return schema;
        if (typeof schema == "string")
          return (0, codegen_1._)`${schema}`;
      }
      return (0, codegen_1._)`${topSchemaRef}${schemaPath}${(0, codegen_1.getProperty)(keyword)}`;
    }
    exports.schemaRefOrVal = schemaRefOrVal;
    function unescapeFragment(str) {
      return unescapeJsonPointer(decodeURIComponent(str));
    }
    exports.unescapeFragment = unescapeFragment;
    function escapeFragment(str) {
      return encodeURIComponent(escapeJsonPointer(str));
    }
    exports.escapeFragment = escapeFragment;
    function escapeJsonPointer(str) {
      return typeof str == "number" ? `${str}` : str.replace(/~/g, "~0").replace(/\//g, "~1");
    }
    exports.escapeJsonPointer = escapeJsonPointer;
    function unescapeJsonPointer(str) {
      return str.replace(/~1/g, "/").replace(/~0/g, "~");
    }
    exports.unescapeJsonPointer = unescapeJsonPointer;
    function eachItem(xs, f) {
      if (Array.isArray(xs))
        for (let x of xs)
          f(x);
      else
        f(xs);
    }
    exports.eachItem = eachItem;
    function makeMergeEvaluated({ mergeNames, mergeToName, mergeValues: mergeValues3, resultToName }) {
      return (gen, from, to, toName) => {
        let res = to === void 0 ? from : to instanceof codegen_1.Name ? (from instanceof codegen_1.Name ? mergeNames(gen, from, to) : mergeToName(gen, from, to), to) : from instanceof codegen_1.Name ? (mergeToName(gen, to, from), from) : mergeValues3(from, to);
        return toName === codegen_1.Name && !(res instanceof codegen_1.Name) ? resultToName(gen, res) : res;
      };
    }
    exports.mergeEvaluated = {
      props: makeMergeEvaluated({
        mergeNames: (gen, from, to) => gen.if((0, codegen_1._)`${to} !== true && ${from} !== undefined`, () => {
          gen.if((0, codegen_1._)`${from} === true`, () => gen.assign(to, !0), () => gen.assign(to, (0, codegen_1._)`${to} || {}`).code((0, codegen_1._)`Object.assign(${to}, ${from})`));
        }),
        mergeToName: (gen, from, to) => gen.if((0, codegen_1._)`${to} !== true`, () => {
          from === !0 ? gen.assign(to, !0) : (gen.assign(to, (0, codegen_1._)`${to} || {}`), setEvaluated(gen, to, from));
        }),
        mergeValues: (from, to) => from === !0 ? !0 : { ...from, ...to },
        resultToName: evaluatedPropsToName
      }),
      items: makeMergeEvaluated({
        mergeNames: (gen, from, to) => gen.if((0, codegen_1._)`${to} !== true && ${from} !== undefined`, () => gen.assign(to, (0, codegen_1._)`${from} === true ? true : ${to} > ${from} ? ${to} : ${from}`)),
        mergeToName: (gen, from, to) => gen.if((0, codegen_1._)`${to} !== true`, () => gen.assign(to, from === !0 ? !0 : (0, codegen_1._)`${to} > ${from} ? ${to} : ${from}`)),
        mergeValues: (from, to) => from === !0 ? !0 : Math.max(from, to),
        resultToName: (gen, items) => gen.var("items", items)
      })
    };
    function evaluatedPropsToName(gen, ps) {
      if (ps === !0)
        return gen.var("props", !0);
      let props = gen.var("props", (0, codegen_1._)`{}`);
      return ps !== void 0 && setEvaluated(gen, props, ps), props;
    }
    exports.evaluatedPropsToName = evaluatedPropsToName;
    function setEvaluated(gen, props, ps) {
      Object.keys(ps).forEach((p) => gen.assign((0, codegen_1._)`${props}${(0, codegen_1.getProperty)(p)}`, !0));
    }
    exports.setEvaluated = setEvaluated;
    var snippets = {};
    function useFunc(gen, f) {
      return gen.scopeValue("func", {
        ref: f,
        code: snippets[f.code] || (snippets[f.code] = new code_1._Code(f.code))
      });
    }
    exports.useFunc = useFunc;
    var Type;
    (function(Type2) {
      Type2[Type2.Num = 0] = "Num", Type2[Type2.Str = 1] = "Str";
    })(Type || (exports.Type = Type = {}));
    function getErrorPath(dataProp, dataPropType, jsPropertySyntax) {
      if (dataProp instanceof codegen_1.Name) {
        let isNumber = dataPropType === Type.Num;
        return jsPropertySyntax ? isNumber ? (0, codegen_1._)`"[" + ${dataProp} + "]"` : (0, codegen_1._)`"['" + ${dataProp} + "']"` : isNumber ? (0, codegen_1._)`"/" + ${dataProp}` : (0, codegen_1._)`"/" + ${dataProp}.replace(/~/g, "~0").replace(/\\//g, "~1")`;
      }
      return jsPropertySyntax ? (0, codegen_1.getProperty)(dataProp).toString() : "/" + escapeJsonPointer(dataProp);
    }
    exports.getErrorPath = getErrorPath;
    function checkStrictMode(it, msg, mode = it.opts.strictSchema) {
      if (mode) {
        if (msg = `strict mode: ${msg}`, mode === !0)
          throw new Error(msg);
        it.self.logger.warn(msg);
      }
    }
    exports.checkStrictMode = checkStrictMode;
  }
});

// node_modules/ajv/dist/compile/names.js
var require_names = __commonJS({
  "node_modules/ajv/dist/compile/names.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), names = {
      // validation function arguments
      data: new codegen_1.Name("data"),
      // data passed to validation function
      // args passed from referencing schema
      valCxt: new codegen_1.Name("valCxt"),
      // validation/data context - should not be used directly, it is destructured to the names below
      instancePath: new codegen_1.Name("instancePath"),
      parentData: new codegen_1.Name("parentData"),
      parentDataProperty: new codegen_1.Name("parentDataProperty"),
      rootData: new codegen_1.Name("rootData"),
      // root data - same as the data passed to the first/top validation function
      dynamicAnchors: new codegen_1.Name("dynamicAnchors"),
      // used to support recursiveRef and dynamicRef
      // function scoped variables
      vErrors: new codegen_1.Name("vErrors"),
      // null or array of validation errors
      errors: new codegen_1.Name("errors"),
      // counter of validation errors
      this: new codegen_1.Name("this"),
      // "globals"
      self: new codegen_1.Name("self"),
      scope: new codegen_1.Name("scope"),
      // JTD serialize/parse name for JSON string and position
      json: new codegen_1.Name("json"),
      jsonPos: new codegen_1.Name("jsonPos"),
      jsonLen: new codegen_1.Name("jsonLen"),
      jsonPart: new codegen_1.Name("jsonPart")
    };
    exports.default = names;
  }
});

// node_modules/ajv/dist/compile/errors.js
var require_errors = __commonJS({
  "node_modules/ajv/dist/compile/errors.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.extendErrors = exports.resetErrorsCount = exports.reportExtraError = exports.reportError = exports.keyword$DataError = exports.keywordError = void 0;
    var codegen_1 = require_codegen(), util_1 = require_util(), names_1 = require_names();
    exports.keywordError = {
      message: ({ keyword }) => (0, codegen_1.str)`must pass "${keyword}" keyword validation`
    };
    exports.keyword$DataError = {
      message: ({ keyword, schemaType }) => schemaType ? (0, codegen_1.str)`"${keyword}" keyword must be ${schemaType} ($data)` : (0, codegen_1.str)`"${keyword}" keyword is invalid ($data)`
    };
    function reportError(cxt, error2 = exports.keywordError, errorPaths, overrideAllErrors) {
      let { it } = cxt, { gen, compositeRule, allErrors } = it, errObj = errorObjectCode(cxt, error2, errorPaths);
      overrideAllErrors ?? (compositeRule || allErrors) ? addError(gen, errObj) : returnErrors(it, (0, codegen_1._)`[${errObj}]`);
    }
    exports.reportError = reportError;
    function reportExtraError(cxt, error2 = exports.keywordError, errorPaths) {
      let { it } = cxt, { gen, compositeRule, allErrors } = it, errObj = errorObjectCode(cxt, error2, errorPaths);
      addError(gen, errObj), compositeRule || allErrors || returnErrors(it, names_1.default.vErrors);
    }
    exports.reportExtraError = reportExtraError;
    function resetErrorsCount(gen, errsCount) {
      gen.assign(names_1.default.errors, errsCount), gen.if((0, codegen_1._)`${names_1.default.vErrors} !== null`, () => gen.if(errsCount, () => gen.assign((0, codegen_1._)`${names_1.default.vErrors}.length`, errsCount), () => gen.assign(names_1.default.vErrors, null)));
    }
    exports.resetErrorsCount = resetErrorsCount;
    function extendErrors({ gen, keyword, schemaValue, data, errsCount, it }) {
      if (errsCount === void 0)
        throw new Error("ajv implementation error");
      let err = gen.name("err");
      gen.forRange("i", errsCount, names_1.default.errors, (i) => {
        gen.const(err, (0, codegen_1._)`${names_1.default.vErrors}[${i}]`), gen.if((0, codegen_1._)`${err}.instancePath === undefined`, () => gen.assign((0, codegen_1._)`${err}.instancePath`, (0, codegen_1.strConcat)(names_1.default.instancePath, it.errorPath))), gen.assign((0, codegen_1._)`${err}.schemaPath`, (0, codegen_1.str)`${it.errSchemaPath}/${keyword}`), it.opts.verbose && (gen.assign((0, codegen_1._)`${err}.schema`, schemaValue), gen.assign((0, codegen_1._)`${err}.data`, data));
      });
    }
    exports.extendErrors = extendErrors;
    function addError(gen, errObj) {
      let err = gen.const("err", errObj);
      gen.if((0, codegen_1._)`${names_1.default.vErrors} === null`, () => gen.assign(names_1.default.vErrors, (0, codegen_1._)`[${err}]`), (0, codegen_1._)`${names_1.default.vErrors}.push(${err})`), gen.code((0, codegen_1._)`${names_1.default.errors}++`);
    }
    function returnErrors(it, errs) {
      let { gen, validateName, schemaEnv } = it;
      schemaEnv.$async ? gen.throw((0, codegen_1._)`new ${it.ValidationError}(${errs})`) : (gen.assign((0, codegen_1._)`${validateName}.errors`, errs), gen.return(!1));
    }
    var E = {
      keyword: new codegen_1.Name("keyword"),
      schemaPath: new codegen_1.Name("schemaPath"),
      // also used in JTD errors
      params: new codegen_1.Name("params"),
      propertyName: new codegen_1.Name("propertyName"),
      message: new codegen_1.Name("message"),
      schema: new codegen_1.Name("schema"),
      parentSchema: new codegen_1.Name("parentSchema")
    };
    function errorObjectCode(cxt, error2, errorPaths) {
      let { createErrors } = cxt.it;
      return createErrors === !1 ? (0, codegen_1._)`{}` : errorObject(cxt, error2, errorPaths);
    }
    function errorObject(cxt, error2, errorPaths = {}) {
      let { gen, it } = cxt, keyValues = [
        errorInstancePath(it, errorPaths),
        errorSchemaPath(cxt, errorPaths)
      ];
      return extraErrorProps(cxt, error2, keyValues), gen.object(...keyValues);
    }
    function errorInstancePath({ errorPath }, { instancePath }) {
      let instPath = instancePath ? (0, codegen_1.str)`${errorPath}${(0, util_1.getErrorPath)(instancePath, util_1.Type.Str)}` : errorPath;
      return [names_1.default.instancePath, (0, codegen_1.strConcat)(names_1.default.instancePath, instPath)];
    }
    function errorSchemaPath({ keyword, it: { errSchemaPath } }, { schemaPath, parentSchema }) {
      let schPath = parentSchema ? errSchemaPath : (0, codegen_1.str)`${errSchemaPath}/${keyword}`;
      return schemaPath && (schPath = (0, codegen_1.str)`${schPath}${(0, util_1.getErrorPath)(schemaPath, util_1.Type.Str)}`), [E.schemaPath, schPath];
    }
    function extraErrorProps(cxt, { params, message }, keyValues) {
      let { keyword, data, schemaValue, it } = cxt, { opts, propertyName, topSchemaRef, schemaPath } = it;
      keyValues.push([E.keyword, keyword], [E.params, typeof params == "function" ? params(cxt) : params || (0, codegen_1._)`{}`]), opts.messages && keyValues.push([E.message, typeof message == "function" ? message(cxt) : message]), opts.verbose && keyValues.push([E.schema, schemaValue], [E.parentSchema, (0, codegen_1._)`${topSchemaRef}${schemaPath}`], [names_1.default.data, data]), propertyName && keyValues.push([E.propertyName, propertyName]);
    }
  }
});

// node_modules/ajv/dist/compile/validate/boolSchema.js
var require_boolSchema = __commonJS({
  "node_modules/ajv/dist/compile/validate/boolSchema.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.boolOrEmptySchema = exports.topBoolOrEmptySchema = void 0;
    var errors_1 = require_errors(), codegen_1 = require_codegen(), names_1 = require_names(), boolError = {
      message: "boolean schema is false"
    };
    function topBoolOrEmptySchema(it) {
      let { gen, schema, validateName } = it;
      schema === !1 ? falseSchemaError(it, !1) : typeof schema == "object" && schema.$async === !0 ? gen.return(names_1.default.data) : (gen.assign((0, codegen_1._)`${validateName}.errors`, null), gen.return(!0));
    }
    exports.topBoolOrEmptySchema = topBoolOrEmptySchema;
    function boolOrEmptySchema(it, valid) {
      let { gen, schema } = it;
      schema === !1 ? (gen.var(valid, !1), falseSchemaError(it)) : gen.var(valid, !0);
    }
    exports.boolOrEmptySchema = boolOrEmptySchema;
    function falseSchemaError(it, overrideAllErrors) {
      let { gen, data } = it, cxt = {
        gen,
        keyword: "false schema",
        data,
        schema: !1,
        schemaCode: !1,
        schemaValue: !1,
        params: {},
        it
      };
      (0, errors_1.reportError)(cxt, boolError, void 0, overrideAllErrors);
    }
  }
});

// node_modules/ajv/dist/compile/rules.js
var require_rules = __commonJS({
  "node_modules/ajv/dist/compile/rules.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.getRules = exports.isJSONType = void 0;
    var _jsonTypes = ["string", "number", "integer", "boolean", "null", "object", "array"], jsonTypes = new Set(_jsonTypes);
    function isJSONType(x) {
      return typeof x == "string" && jsonTypes.has(x);
    }
    exports.isJSONType = isJSONType;
    function getRules() {
      let groups = {
        number: { type: "number", rules: [] },
        string: { type: "string", rules: [] },
        array: { type: "array", rules: [] },
        object: { type: "object", rules: [] }
      };
      return {
        types: { ...groups, integer: !0, boolean: !0, null: !0 },
        rules: [{ rules: [] }, groups.number, groups.string, groups.array, groups.object],
        post: { rules: [] },
        all: {},
        keywords: {}
      };
    }
    exports.getRules = getRules;
  }
});

// node_modules/ajv/dist/compile/validate/applicability.js
var require_applicability = __commonJS({
  "node_modules/ajv/dist/compile/validate/applicability.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.shouldUseRule = exports.shouldUseGroup = exports.schemaHasRulesForType = void 0;
    function schemaHasRulesForType({ schema, self }, type) {
      let group = self.RULES.types[type];
      return group && group !== !0 && shouldUseGroup(schema, group);
    }
    exports.schemaHasRulesForType = schemaHasRulesForType;
    function shouldUseGroup(schema, group) {
      return group.rules.some((rule) => shouldUseRule(schema, rule));
    }
    exports.shouldUseGroup = shouldUseGroup;
    function shouldUseRule(schema, rule) {
      var _a;
      return schema[rule.keyword] !== void 0 || ((_a = rule.definition.implements) === null || _a === void 0 ? void 0 : _a.some((kwd) => schema[kwd] !== void 0));
    }
    exports.shouldUseRule = shouldUseRule;
  }
});

// node_modules/ajv/dist/compile/validate/dataType.js
var require_dataType = __commonJS({
  "node_modules/ajv/dist/compile/validate/dataType.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.reportTypeError = exports.checkDataTypes = exports.checkDataType = exports.coerceAndCheckDataType = exports.getJSONTypes = exports.getSchemaTypes = exports.DataType = void 0;
    var rules_1 = require_rules(), applicability_1 = require_applicability(), errors_1 = require_errors(), codegen_1 = require_codegen(), util_1 = require_util(), DataType;
    (function(DataType2) {
      DataType2[DataType2.Correct = 0] = "Correct", DataType2[DataType2.Wrong = 1] = "Wrong";
    })(DataType || (exports.DataType = DataType = {}));
    function getSchemaTypes(schema) {
      let types = getJSONTypes(schema.type);
      if (types.includes("null")) {
        if (schema.nullable === !1)
          throw new Error("type: null contradicts nullable: false");
      } else {
        if (!types.length && schema.nullable !== void 0)
          throw new Error('"nullable" cannot be used without "type"');
        schema.nullable === !0 && types.push("null");
      }
      return types;
    }
    exports.getSchemaTypes = getSchemaTypes;
    function getJSONTypes(ts) {
      let types = Array.isArray(ts) ? ts : ts ? [ts] : [];
      if (types.every(rules_1.isJSONType))
        return types;
      throw new Error("type must be JSONType or JSONType[]: " + types.join(","));
    }
    exports.getJSONTypes = getJSONTypes;
    function coerceAndCheckDataType(it, types) {
      let { gen, data, opts } = it, coerceTo = coerceToTypes(types, opts.coerceTypes), checkTypes = types.length > 0 && !(coerceTo.length === 0 && types.length === 1 && (0, applicability_1.schemaHasRulesForType)(it, types[0]));
      if (checkTypes) {
        let wrongType = checkDataTypes(types, data, opts.strictNumbers, DataType.Wrong);
        gen.if(wrongType, () => {
          coerceTo.length ? coerceData(it, types, coerceTo) : reportTypeError(it);
        });
      }
      return checkTypes;
    }
    exports.coerceAndCheckDataType = coerceAndCheckDataType;
    var COERCIBLE = /* @__PURE__ */ new Set(["string", "number", "integer", "boolean", "null"]);
    function coerceToTypes(types, coerceTypes) {
      return coerceTypes ? types.filter((t) => COERCIBLE.has(t) || coerceTypes === "array" && t === "array") : [];
    }
    function coerceData(it, types, coerceTo) {
      let { gen, data, opts } = it, dataType = gen.let("dataType", (0, codegen_1._)`typeof ${data}`), coerced = gen.let("coerced", (0, codegen_1._)`undefined`);
      opts.coerceTypes === "array" && gen.if((0, codegen_1._)`${dataType} == 'object' && Array.isArray(${data}) && ${data}.length == 1`, () => gen.assign(data, (0, codegen_1._)`${data}[0]`).assign(dataType, (0, codegen_1._)`typeof ${data}`).if(checkDataTypes(types, data, opts.strictNumbers), () => gen.assign(coerced, data))), gen.if((0, codegen_1._)`${coerced} !== undefined`);
      for (let t of coerceTo)
        (COERCIBLE.has(t) || t === "array" && opts.coerceTypes === "array") && coerceSpecificType(t);
      gen.else(), reportTypeError(it), gen.endIf(), gen.if((0, codegen_1._)`${coerced} !== undefined`, () => {
        gen.assign(data, coerced), assignParentData(it, coerced);
      });
      function coerceSpecificType(t) {
        switch (t) {
          case "string":
            gen.elseIf((0, codegen_1._)`${dataType} == "number" || ${dataType} == "boolean"`).assign(coerced, (0, codegen_1._)`"" + ${data}`).elseIf((0, codegen_1._)`${data} === null`).assign(coerced, (0, codegen_1._)`""`);
            return;
          case "number":
            gen.elseIf((0, codegen_1._)`${dataType} == "boolean" || ${data} === null
              || (${dataType} == "string" && ${data} && ${data} == +${data})`).assign(coerced, (0, codegen_1._)`+${data}`);
            return;
          case "integer":
            gen.elseIf((0, codegen_1._)`${dataType} === "boolean" || ${data} === null
              || (${dataType} === "string" && ${data} && ${data} == +${data} && !(${data} % 1))`).assign(coerced, (0, codegen_1._)`+${data}`);
            return;
          case "boolean":
            gen.elseIf((0, codegen_1._)`${data} === "false" || ${data} === 0 || ${data} === null`).assign(coerced, !1).elseIf((0, codegen_1._)`${data} === "true" || ${data} === 1`).assign(coerced, !0);
            return;
          case "null":
            gen.elseIf((0, codegen_1._)`${data} === "" || ${data} === 0 || ${data} === false`), gen.assign(coerced, null);
            return;
          case "array":
            gen.elseIf((0, codegen_1._)`${dataType} === "string" || ${dataType} === "number"
              || ${dataType} === "boolean" || ${data} === null`).assign(coerced, (0, codegen_1._)`[${data}]`);
        }
      }
    }
    function assignParentData({ gen, parentData, parentDataProperty }, expr) {
      gen.if((0, codegen_1._)`${parentData} !== undefined`, () => gen.assign((0, codegen_1._)`${parentData}[${parentDataProperty}]`, expr));
    }
    function checkDataType(dataType, data, strictNums, correct = DataType.Correct) {
      let EQ = correct === DataType.Correct ? codegen_1.operators.EQ : codegen_1.operators.NEQ, cond;
      switch (dataType) {
        case "null":
          return (0, codegen_1._)`${data} ${EQ} null`;
        case "array":
          cond = (0, codegen_1._)`Array.isArray(${data})`;
          break;
        case "object":
          cond = (0, codegen_1._)`${data} && typeof ${data} == "object" && !Array.isArray(${data})`;
          break;
        case "integer":
          cond = numCond((0, codegen_1._)`!(${data} % 1) && !isNaN(${data})`);
          break;
        case "number":
          cond = numCond();
          break;
        default:
          return (0, codegen_1._)`typeof ${data} ${EQ} ${dataType}`;
      }
      return correct === DataType.Correct ? cond : (0, codegen_1.not)(cond);
      function numCond(_cond = codegen_1.nil) {
        return (0, codegen_1.and)((0, codegen_1._)`typeof ${data} == "number"`, _cond, strictNums ? (0, codegen_1._)`isFinite(${data})` : codegen_1.nil);
      }
    }
    exports.checkDataType = checkDataType;
    function checkDataTypes(dataTypes, data, strictNums, correct) {
      if (dataTypes.length === 1)
        return checkDataType(dataTypes[0], data, strictNums, correct);
      let cond, types = (0, util_1.toHash)(dataTypes);
      if (types.array && types.object) {
        let notObj = (0, codegen_1._)`typeof ${data} != "object"`;
        cond = types.null ? notObj : (0, codegen_1._)`!${data} || ${notObj}`, delete types.null, delete types.array, delete types.object;
      } else
        cond = codegen_1.nil;
      types.number && delete types.integer;
      for (let t in types)
        cond = (0, codegen_1.and)(cond, checkDataType(t, data, strictNums, correct));
      return cond;
    }
    exports.checkDataTypes = checkDataTypes;
    var typeError = {
      message: ({ schema }) => `must be ${schema}`,
      params: ({ schema, schemaValue }) => typeof schema == "string" ? (0, codegen_1._)`{type: ${schema}}` : (0, codegen_1._)`{type: ${schemaValue}}`
    };
    function reportTypeError(it) {
      let cxt = getTypeErrorContext(it);
      (0, errors_1.reportError)(cxt, typeError);
    }
    exports.reportTypeError = reportTypeError;
    function getTypeErrorContext(it) {
      let { gen, data, schema } = it, schemaCode = (0, util_1.schemaRefOrVal)(it, schema, "type");
      return {
        gen,
        keyword: "type",
        data,
        schema: schema.type,
        schemaCode,
        schemaValue: schemaCode,
        parentSchema: schema,
        params: {},
        it
      };
    }
  }
});

// node_modules/ajv/dist/compile/validate/defaults.js
var require_defaults = __commonJS({
  "node_modules/ajv/dist/compile/validate/defaults.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.assignDefaults = void 0;
    var codegen_1 = require_codegen(), util_1 = require_util();
    function assignDefaults(it, ty) {
      let { properties, items } = it.schema;
      if (ty === "object" && properties)
        for (let key in properties)
          assignDefault(it, key, properties[key].default);
      else ty === "array" && Array.isArray(items) && items.forEach((sch, i) => assignDefault(it, i, sch.default));
    }
    exports.assignDefaults = assignDefaults;
    function assignDefault(it, prop, defaultValue) {
      let { gen, compositeRule, data, opts } = it;
      if (defaultValue === void 0)
        return;
      let childData = (0, codegen_1._)`${data}${(0, codegen_1.getProperty)(prop)}`;
      if (compositeRule) {
        (0, util_1.checkStrictMode)(it, `default is ignored for: ${childData}`);
        return;
      }
      let condition = (0, codegen_1._)`${childData} === undefined`;
      opts.useDefaults === "empty" && (condition = (0, codegen_1._)`${condition} || ${childData} === null || ${childData} === ""`), gen.if(condition, (0, codegen_1._)`${childData} = ${(0, codegen_1.stringify)(defaultValue)}`);
    }
  }
});

// node_modules/ajv/dist/vocabularies/code.js
var require_code2 = __commonJS({
  "node_modules/ajv/dist/vocabularies/code.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.validateUnion = exports.validateArray = exports.usePattern = exports.callValidateCode = exports.schemaProperties = exports.allSchemaProperties = exports.noPropertyInData = exports.propertyInData = exports.isOwnProperty = exports.hasPropFunc = exports.reportMissingProp = exports.checkMissingProp = exports.checkReportMissingProp = void 0;
    var codegen_1 = require_codegen(), util_1 = require_util(), names_1 = require_names(), util_2 = require_util();
    function checkReportMissingProp(cxt, prop) {
      let { gen, data, it } = cxt;
      gen.if(noPropertyInData(gen, data, prop, it.opts.ownProperties), () => {
        cxt.setParams({ missingProperty: (0, codegen_1._)`${prop}` }, !0), cxt.error();
      });
    }
    exports.checkReportMissingProp = checkReportMissingProp;
    function checkMissingProp({ gen, data, it: { opts } }, properties, missing) {
      return (0, codegen_1.or)(...properties.map((prop) => (0, codegen_1.and)(noPropertyInData(gen, data, prop, opts.ownProperties), (0, codegen_1._)`${missing} = ${prop}`)));
    }
    exports.checkMissingProp = checkMissingProp;
    function reportMissingProp(cxt, missing) {
      cxt.setParams({ missingProperty: missing }, !0), cxt.error();
    }
    exports.reportMissingProp = reportMissingProp;
    function hasPropFunc(gen) {
      return gen.scopeValue("func", {
        // eslint-disable-next-line @typescript-eslint/unbound-method
        ref: Object.prototype.hasOwnProperty,
        code: (0, codegen_1._)`Object.prototype.hasOwnProperty`
      });
    }
    exports.hasPropFunc = hasPropFunc;
    function isOwnProperty(gen, data, property) {
      return (0, codegen_1._)`${hasPropFunc(gen)}.call(${data}, ${property})`;
    }
    exports.isOwnProperty = isOwnProperty;
    function propertyInData(gen, data, property, ownProperties) {
      let cond = (0, codegen_1._)`${data}${(0, codegen_1.getProperty)(property)} !== undefined`;
      return ownProperties ? (0, codegen_1._)`${cond} && ${isOwnProperty(gen, data, property)}` : cond;
    }
    exports.propertyInData = propertyInData;
    function noPropertyInData(gen, data, property, ownProperties) {
      let cond = (0, codegen_1._)`${data}${(0, codegen_1.getProperty)(property)} === undefined`;
      return ownProperties ? (0, codegen_1.or)(cond, (0, codegen_1.not)(isOwnProperty(gen, data, property))) : cond;
    }
    exports.noPropertyInData = noPropertyInData;
    function allSchemaProperties(schemaMap) {
      return schemaMap ? Object.keys(schemaMap).filter((p) => p !== "__proto__") : [];
    }
    exports.allSchemaProperties = allSchemaProperties;
    function schemaProperties(it, schemaMap) {
      return allSchemaProperties(schemaMap).filter((p) => !(0, util_1.alwaysValidSchema)(it, schemaMap[p]));
    }
    exports.schemaProperties = schemaProperties;
    function callValidateCode({ schemaCode, data, it: { gen, topSchemaRef, schemaPath, errorPath }, it }, func, context, passSchema) {
      let dataAndSchema = passSchema ? (0, codegen_1._)`${schemaCode}, ${data}, ${topSchemaRef}${schemaPath}` : data, valCxt = [
        [names_1.default.instancePath, (0, codegen_1.strConcat)(names_1.default.instancePath, errorPath)],
        [names_1.default.parentData, it.parentData],
        [names_1.default.parentDataProperty, it.parentDataProperty],
        [names_1.default.rootData, names_1.default.rootData]
      ];
      it.opts.dynamicRef && valCxt.push([names_1.default.dynamicAnchors, names_1.default.dynamicAnchors]);
      let args = (0, codegen_1._)`${dataAndSchema}, ${gen.object(...valCxt)}`;
      return context !== codegen_1.nil ? (0, codegen_1._)`${func}.call(${context}, ${args})` : (0, codegen_1._)`${func}(${args})`;
    }
    exports.callValidateCode = callValidateCode;
    var newRegExp = (0, codegen_1._)`new RegExp`;
    function usePattern({ gen, it: { opts } }, pattern) {
      let u = opts.unicodeRegExp ? "u" : "", { regExp } = opts.code, rx = regExp(pattern, u);
      return gen.scopeValue("pattern", {
        key: rx.toString(),
        ref: rx,
        code: (0, codegen_1._)`${regExp.code === "new RegExp" ? newRegExp : (0, util_2.useFunc)(gen, regExp)}(${pattern}, ${u})`
      });
    }
    exports.usePattern = usePattern;
    function validateArray(cxt) {
      let { gen, data, keyword, it } = cxt, valid = gen.name("valid");
      if (it.allErrors) {
        let validArr = gen.let("valid", !0);
        return validateItems(() => gen.assign(validArr, !1)), validArr;
      }
      return gen.var(valid, !0), validateItems(() => gen.break()), valid;
      function validateItems(notValid) {
        let len = gen.const("len", (0, codegen_1._)`${data}.length`);
        gen.forRange("i", 0, len, (i) => {
          cxt.subschema({
            keyword,
            dataProp: i,
            dataPropType: util_1.Type.Num
          }, valid), gen.if((0, codegen_1.not)(valid), notValid);
        });
      }
    }
    exports.validateArray = validateArray;
    function validateUnion(cxt) {
      let { gen, schema, keyword, it } = cxt;
      if (!Array.isArray(schema))
        throw new Error("ajv implementation error");
      if (schema.some((sch) => (0, util_1.alwaysValidSchema)(it, sch)) && !it.opts.unevaluated)
        return;
      let valid = gen.let("valid", !1), schValid = gen.name("_valid");
      gen.block(() => schema.forEach((_sch, i) => {
        let schCxt = cxt.subschema({
          keyword,
          schemaProp: i,
          compositeRule: !0
        }, schValid);
        gen.assign(valid, (0, codegen_1._)`${valid} || ${schValid}`), cxt.mergeValidEvaluated(schCxt, schValid) || gen.if((0, codegen_1.not)(valid));
      })), cxt.result(valid, () => cxt.reset(), () => cxt.error(!0));
    }
    exports.validateUnion = validateUnion;
  }
});

// node_modules/ajv/dist/compile/validate/keyword.js
var require_keyword = __commonJS({
  "node_modules/ajv/dist/compile/validate/keyword.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.validateKeywordUsage = exports.validSchemaType = exports.funcKeywordCode = exports.macroKeywordCode = void 0;
    var codegen_1 = require_codegen(), names_1 = require_names(), code_1 = require_code2(), errors_1 = require_errors();
    function macroKeywordCode(cxt, def) {
      let { gen, keyword, schema, parentSchema, it } = cxt, macroSchema = def.macro.call(it.self, schema, parentSchema, it), schemaRef = useKeyword(gen, keyword, macroSchema);
      it.opts.validateSchema !== !1 && it.self.validateSchema(macroSchema, !0);
      let valid = gen.name("valid");
      cxt.subschema({
        schema: macroSchema,
        schemaPath: codegen_1.nil,
        errSchemaPath: `${it.errSchemaPath}/${keyword}`,
        topSchemaRef: schemaRef,
        compositeRule: !0
      }, valid), cxt.pass(valid, () => cxt.error(!0));
    }
    exports.macroKeywordCode = macroKeywordCode;
    function funcKeywordCode(cxt, def) {
      var _a;
      let { gen, keyword, schema, parentSchema, $data, it } = cxt;
      checkAsyncKeyword(it, def);
      let validate = !$data && def.compile ? def.compile.call(it.self, schema, parentSchema, it) : def.validate, validateRef = useKeyword(gen, keyword, validate), valid = gen.let("valid");
      cxt.block$data(valid, validateKeyword), cxt.ok((_a = def.valid) !== null && _a !== void 0 ? _a : valid);
      function validateKeyword() {
        if (def.errors === !1)
          assignValid(), def.modifying && modifyData(cxt), reportErrs(() => cxt.error());
        else {
          let ruleErrs = def.async ? validateAsync() : validateSync();
          def.modifying && modifyData(cxt), reportErrs(() => addErrs(cxt, ruleErrs));
        }
      }
      function validateAsync() {
        let ruleErrs = gen.let("ruleErrs", null);
        return gen.try(() => assignValid((0, codegen_1._)`await `), (e) => gen.assign(valid, !1).if((0, codegen_1._)`${e} instanceof ${it.ValidationError}`, () => gen.assign(ruleErrs, (0, codegen_1._)`${e}.errors`), () => gen.throw(e))), ruleErrs;
      }
      function validateSync() {
        let validateErrs = (0, codegen_1._)`${validateRef}.errors`;
        return gen.assign(validateErrs, null), assignValid(codegen_1.nil), validateErrs;
      }
      function assignValid(_await = def.async ? (0, codegen_1._)`await ` : codegen_1.nil) {
        let passCxt = it.opts.passContext ? names_1.default.this : names_1.default.self, passSchema = !("compile" in def && !$data || def.schema === !1);
        gen.assign(valid, (0, codegen_1._)`${_await}${(0, code_1.callValidateCode)(cxt, validateRef, passCxt, passSchema)}`, def.modifying);
      }
      function reportErrs(errors) {
        var _a2;
        gen.if((0, codegen_1.not)((_a2 = def.valid) !== null && _a2 !== void 0 ? _a2 : valid), errors);
      }
    }
    exports.funcKeywordCode = funcKeywordCode;
    function modifyData(cxt) {
      let { gen, data, it } = cxt;
      gen.if(it.parentData, () => gen.assign(data, (0, codegen_1._)`${it.parentData}[${it.parentDataProperty}]`));
    }
    function addErrs(cxt, errs) {
      let { gen } = cxt;
      gen.if((0, codegen_1._)`Array.isArray(${errs})`, () => {
        gen.assign(names_1.default.vErrors, (0, codegen_1._)`${names_1.default.vErrors} === null ? ${errs} : ${names_1.default.vErrors}.concat(${errs})`).assign(names_1.default.errors, (0, codegen_1._)`${names_1.default.vErrors}.length`), (0, errors_1.extendErrors)(cxt);
      }, () => cxt.error());
    }
    function checkAsyncKeyword({ schemaEnv }, def) {
      if (def.async && !schemaEnv.$async)
        throw new Error("async keyword in sync schema");
    }
    function useKeyword(gen, keyword, result) {
      if (result === void 0)
        throw new Error(`keyword "${keyword}" failed to compile`);
      return gen.scopeValue("keyword", typeof result == "function" ? { ref: result } : { ref: result, code: (0, codegen_1.stringify)(result) });
    }
    function validSchemaType(schema, schemaType, allowUndefined = !1) {
      return !schemaType.length || schemaType.some((st) => st === "array" ? Array.isArray(schema) : st === "object" ? schema && typeof schema == "object" && !Array.isArray(schema) : typeof schema == st || allowUndefined && typeof schema > "u");
    }
    exports.validSchemaType = validSchemaType;
    function validateKeywordUsage({ schema, opts, self, errSchemaPath }, def, keyword) {
      if (Array.isArray(def.keyword) ? !def.keyword.includes(keyword) : def.keyword !== keyword)
        throw new Error("ajv implementation error");
      let deps = def.dependencies;
      if (deps?.some((kwd) => !Object.prototype.hasOwnProperty.call(schema, kwd)))
        throw new Error(`parent schema must have dependencies of ${keyword}: ${deps.join(",")}`);
      if (def.validateSchema && !def.validateSchema(schema[keyword])) {
        let msg = `keyword "${keyword}" value is invalid at path "${errSchemaPath}": ` + self.errorsText(def.validateSchema.errors);
        if (opts.validateSchema === "log")
          self.logger.error(msg);
        else
          throw new Error(msg);
      }
    }
    exports.validateKeywordUsage = validateKeywordUsage;
  }
});

// node_modules/ajv/dist/compile/validate/subschema.js
var require_subschema = __commonJS({
  "node_modules/ajv/dist/compile/validate/subschema.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.extendSubschemaMode = exports.extendSubschemaData = exports.getSubschema = void 0;
    var codegen_1 = require_codegen(), util_1 = require_util();
    function getSubschema(it, { keyword, schemaProp, schema, schemaPath, errSchemaPath, topSchemaRef }) {
      if (keyword !== void 0 && schema !== void 0)
        throw new Error('both "keyword" and "schema" passed, only one allowed');
      if (keyword !== void 0) {
        let sch = it.schema[keyword];
        return schemaProp === void 0 ? {
          schema: sch,
          schemaPath: (0, codegen_1._)`${it.schemaPath}${(0, codegen_1.getProperty)(keyword)}`,
          errSchemaPath: `${it.errSchemaPath}/${keyword}`
        } : {
          schema: sch[schemaProp],
          schemaPath: (0, codegen_1._)`${it.schemaPath}${(0, codegen_1.getProperty)(keyword)}${(0, codegen_1.getProperty)(schemaProp)}`,
          errSchemaPath: `${it.errSchemaPath}/${keyword}/${(0, util_1.escapeFragment)(schemaProp)}`
        };
      }
      if (schema !== void 0) {
        if (schemaPath === void 0 || errSchemaPath === void 0 || topSchemaRef === void 0)
          throw new Error('"schemaPath", "errSchemaPath" and "topSchemaRef" are required with "schema"');
        return {
          schema,
          schemaPath,
          topSchemaRef,
          errSchemaPath
        };
      }
      throw new Error('either "keyword" or "schema" must be passed');
    }
    exports.getSubschema = getSubschema;
    function extendSubschemaData(subschema, it, { dataProp, dataPropType: dpType, data, dataTypes, propertyName }) {
      if (data !== void 0 && dataProp !== void 0)
        throw new Error('both "data" and "dataProp" passed, only one allowed');
      let { gen } = it;
      if (dataProp !== void 0) {
        let { errorPath, dataPathArr, opts } = it, nextData = gen.let("data", (0, codegen_1._)`${it.data}${(0, codegen_1.getProperty)(dataProp)}`, !0);
        dataContextProps(nextData), subschema.errorPath = (0, codegen_1.str)`${errorPath}${(0, util_1.getErrorPath)(dataProp, dpType, opts.jsPropertySyntax)}`, subschema.parentDataProperty = (0, codegen_1._)`${dataProp}`, subschema.dataPathArr = [...dataPathArr, subschema.parentDataProperty];
      }
      if (data !== void 0) {
        let nextData = data instanceof codegen_1.Name ? data : gen.let("data", data, !0);
        dataContextProps(nextData), propertyName !== void 0 && (subschema.propertyName = propertyName);
      }
      dataTypes && (subschema.dataTypes = dataTypes);
      function dataContextProps(_nextData) {
        subschema.data = _nextData, subschema.dataLevel = it.dataLevel + 1, subschema.dataTypes = [], it.definedProperties = /* @__PURE__ */ new Set(), subschema.parentData = it.data, subschema.dataNames = [...it.dataNames, _nextData];
      }
    }
    exports.extendSubschemaData = extendSubschemaData;
    function extendSubschemaMode(subschema, { jtdDiscriminator, jtdMetadata, compositeRule, createErrors, allErrors }) {
      compositeRule !== void 0 && (subschema.compositeRule = compositeRule), createErrors !== void 0 && (subschema.createErrors = createErrors), allErrors !== void 0 && (subschema.allErrors = allErrors), subschema.jtdDiscriminator = jtdDiscriminator, subschema.jtdMetadata = jtdMetadata;
    }
    exports.extendSubschemaMode = extendSubschemaMode;
  }
});

// node_modules/fast-deep-equal/index.js
var require_fast_deep_equal = __commonJS({
  "node_modules/fast-deep-equal/index.js"(exports, module) {
    "use strict";
    module.exports = function equal(a, b) {
      if (a === b) return !0;
      if (a && b && typeof a == "object" && typeof b == "object") {
        if (a.constructor !== b.constructor) return !1;
        var length, i, keys;
        if (Array.isArray(a)) {
          if (length = a.length, length != b.length) return !1;
          for (i = length; i-- !== 0; )
            if (!equal(a[i], b[i])) return !1;
          return !0;
        }
        if (a.constructor === RegExp) return a.source === b.source && a.flags === b.flags;
        if (a.valueOf !== Object.prototype.valueOf) return a.valueOf() === b.valueOf();
        if (a.toString !== Object.prototype.toString) return a.toString() === b.toString();
        if (keys = Object.keys(a), length = keys.length, length !== Object.keys(b).length) return !1;
        for (i = length; i-- !== 0; )
          if (!Object.prototype.hasOwnProperty.call(b, keys[i])) return !1;
        for (i = length; i-- !== 0; ) {
          var key = keys[i];
          if (!equal(a[key], b[key])) return !1;
        }
        return !0;
      }
      return a !== a && b !== b;
    };
  }
});

// node_modules/json-schema-traverse/index.js
var require_json_schema_traverse = __commonJS({
  "node_modules/json-schema-traverse/index.js"(exports, module) {
    "use strict";
    var traverse = module.exports = function(schema, opts, cb) {
      typeof opts == "function" && (cb = opts, opts = {}), cb = opts.cb || cb;
      var pre = typeof cb == "function" ? cb : cb.pre || function() {
      }, post = cb.post || function() {
      };
      _traverse(opts, pre, post, schema, "", schema);
    };
    traverse.keywords = {
      additionalItems: !0,
      items: !0,
      contains: !0,
      additionalProperties: !0,
      propertyNames: !0,
      not: !0,
      if: !0,
      then: !0,
      else: !0
    };
    traverse.arrayKeywords = {
      items: !0,
      allOf: !0,
      anyOf: !0,
      oneOf: !0
    };
    traverse.propsKeywords = {
      $defs: !0,
      definitions: !0,
      properties: !0,
      patternProperties: !0,
      dependencies: !0
    };
    traverse.skipKeywords = {
      default: !0,
      enum: !0,
      const: !0,
      required: !0,
      maximum: !0,
      minimum: !0,
      exclusiveMaximum: !0,
      exclusiveMinimum: !0,
      multipleOf: !0,
      maxLength: !0,
      minLength: !0,
      pattern: !0,
      format: !0,
      maxItems: !0,
      minItems: !0,
      uniqueItems: !0,
      maxProperties: !0,
      minProperties: !0
    };
    function _traverse(opts, pre, post, schema, jsonPtr, rootSchema, parentJsonPtr, parentKeyword, parentSchema, keyIndex) {
      if (schema && typeof schema == "object" && !Array.isArray(schema)) {
        pre(schema, jsonPtr, rootSchema, parentJsonPtr, parentKeyword, parentSchema, keyIndex);
        for (var key in schema) {
          var sch = schema[key];
          if (Array.isArray(sch)) {
            if (key in traverse.arrayKeywords)
              for (var i = 0; i < sch.length; i++)
                _traverse(opts, pre, post, sch[i], jsonPtr + "/" + key + "/" + i, rootSchema, jsonPtr, key, schema, i);
          } else if (key in traverse.propsKeywords) {
            if (sch && typeof sch == "object")
              for (var prop in sch)
                _traverse(opts, pre, post, sch[prop], jsonPtr + "/" + key + "/" + escapeJsonPtr(prop), rootSchema, jsonPtr, key, schema, prop);
          } else (key in traverse.keywords || opts.allKeys && !(key in traverse.skipKeywords)) && _traverse(opts, pre, post, sch, jsonPtr + "/" + key, rootSchema, jsonPtr, key, schema);
        }
        post(schema, jsonPtr, rootSchema, parentJsonPtr, parentKeyword, parentSchema, keyIndex);
      }
    }
    function escapeJsonPtr(str) {
      return str.replace(/~/g, "~0").replace(/\//g, "~1");
    }
  }
});

// node_modules/ajv/dist/compile/resolve.js
var require_resolve = __commonJS({
  "node_modules/ajv/dist/compile/resolve.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.getSchemaRefs = exports.resolveUrl = exports.normalizeId = exports._getFullPath = exports.getFullPath = exports.inlineRef = void 0;
    var util_1 = require_util(), equal = require_fast_deep_equal(), traverse = require_json_schema_traverse(), SIMPLE_INLINED = /* @__PURE__ */ new Set([
      "type",
      "format",
      "pattern",
      "maxLength",
      "minLength",
      "maxProperties",
      "minProperties",
      "maxItems",
      "minItems",
      "maximum",
      "minimum",
      "uniqueItems",
      "multipleOf",
      "required",
      "enum",
      "const"
    ]);
    function inlineRef(schema, limit = !0) {
      return typeof schema == "boolean" ? !0 : limit === !0 ? !hasRef(schema) : limit ? countKeys(schema) <= limit : !1;
    }
    exports.inlineRef = inlineRef;
    var REF_KEYWORDS = /* @__PURE__ */ new Set([
      "$ref",
      "$recursiveRef",
      "$recursiveAnchor",
      "$dynamicRef",
      "$dynamicAnchor"
    ]);
    function hasRef(schema) {
      for (let key in schema) {
        if (REF_KEYWORDS.has(key))
          return !0;
        let sch = schema[key];
        if (Array.isArray(sch) && sch.some(hasRef) || typeof sch == "object" && hasRef(sch))
          return !0;
      }
      return !1;
    }
    function countKeys(schema) {
      let count = 0;
      for (let key in schema) {
        if (key === "$ref")
          return 1 / 0;
        if (count++, !SIMPLE_INLINED.has(key) && (typeof schema[key] == "object" && (0, util_1.eachItem)(schema[key], (sch) => count += countKeys(sch)), count === 1 / 0))
          return 1 / 0;
      }
      return count;
    }
    function getFullPath(resolver, id = "", normalize) {
      normalize !== !1 && (id = normalizeId(id));
      let p = resolver.parse(id);
      return _getFullPath(resolver, p);
    }
    exports.getFullPath = getFullPath;
    function _getFullPath(resolver, p) {
      return resolver.serialize(p).split("#")[0] + "#";
    }
    exports._getFullPath = _getFullPath;
    var TRAILING_SLASH_HASH = /#\/?$/;
    function normalizeId(id) {
      return id ? id.replace(TRAILING_SLASH_HASH, "") : "";
    }
    exports.normalizeId = normalizeId;
    function resolveUrl(resolver, baseId, id) {
      return id = normalizeId(id), resolver.resolve(baseId, id);
    }
    exports.resolveUrl = resolveUrl;
    var ANCHOR = /^[a-z_][-a-z0-9._]*$/i;
    function getSchemaRefs(schema, baseId) {
      if (typeof schema == "boolean")
        return {};
      let { schemaId, uriResolver } = this.opts, schId = normalizeId(schema[schemaId] || baseId), baseIds = { "": schId }, pathPrefix = getFullPath(uriResolver, schId, !1), localRefs = {}, schemaRefs = /* @__PURE__ */ new Set();
      return traverse(schema, { allKeys: !0 }, (sch, jsonPtr, _, parentJsonPtr) => {
        if (parentJsonPtr === void 0)
          return;
        let fullPath = pathPrefix + jsonPtr, innerBaseId = baseIds[parentJsonPtr];
        typeof sch[schemaId] == "string" && (innerBaseId = addRef.call(this, sch[schemaId])), addAnchor.call(this, sch.$anchor), addAnchor.call(this, sch.$dynamicAnchor), baseIds[jsonPtr] = innerBaseId;
        function addRef(ref2) {
          let _resolve = this.opts.uriResolver.resolve;
          if (ref2 = normalizeId(innerBaseId ? _resolve(innerBaseId, ref2) : ref2), schemaRefs.has(ref2))
            throw ambiguos(ref2);
          schemaRefs.add(ref2);
          let schOrRef = this.refs[ref2];
          return typeof schOrRef == "string" && (schOrRef = this.refs[schOrRef]), typeof schOrRef == "object" ? checkAmbiguosRef(sch, schOrRef.schema, ref2) : ref2 !== normalizeId(fullPath) && (ref2[0] === "#" ? (checkAmbiguosRef(sch, localRefs[ref2], ref2), localRefs[ref2] = sch) : this.refs[ref2] = fullPath), ref2;
        }
        function addAnchor(anchor) {
          if (typeof anchor == "string") {
            if (!ANCHOR.test(anchor))
              throw new Error(`invalid anchor "${anchor}"`);
            addRef.call(this, `#${anchor}`);
          }
        }
      }), localRefs;
      function checkAmbiguosRef(sch1, sch2, ref2) {
        if (sch2 !== void 0 && !equal(sch1, sch2))
          throw ambiguos(ref2);
      }
      function ambiguos(ref2) {
        return new Error(`reference "${ref2}" resolves to more than one schema`);
      }
    }
    exports.getSchemaRefs = getSchemaRefs;
  }
});

// node_modules/ajv/dist/compile/validate/index.js
var require_validate = __commonJS({
  "node_modules/ajv/dist/compile/validate/index.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.getData = exports.KeywordCxt = exports.validateFunctionCode = void 0;
    var boolSchema_1 = require_boolSchema(), dataType_1 = require_dataType(), applicability_1 = require_applicability(), dataType_2 = require_dataType(), defaults_1 = require_defaults(), keyword_1 = require_keyword(), subschema_1 = require_subschema(), codegen_1 = require_codegen(), names_1 = require_names(), resolve_1 = require_resolve(), util_1 = require_util(), errors_1 = require_errors();
    function validateFunctionCode(it) {
      if (isSchemaObj(it) && (checkKeywords(it), schemaCxtHasRules(it))) {
        topSchemaObjCode(it);
        return;
      }
      validateFunction(it, () => (0, boolSchema_1.topBoolOrEmptySchema)(it));
    }
    exports.validateFunctionCode = validateFunctionCode;
    function validateFunction({ gen, validateName, schema, schemaEnv, opts }, body) {
      opts.code.es5 ? gen.func(validateName, (0, codegen_1._)`${names_1.default.data}, ${names_1.default.valCxt}`, schemaEnv.$async, () => {
        gen.code((0, codegen_1._)`"use strict"; ${funcSourceUrl(schema, opts)}`), destructureValCxtES5(gen, opts), gen.code(body);
      }) : gen.func(validateName, (0, codegen_1._)`${names_1.default.data}, ${destructureValCxt(opts)}`, schemaEnv.$async, () => gen.code(funcSourceUrl(schema, opts)).code(body));
    }
    function destructureValCxt(opts) {
      return (0, codegen_1._)`{${names_1.default.instancePath}="", ${names_1.default.parentData}, ${names_1.default.parentDataProperty}, ${names_1.default.rootData}=${names_1.default.data}${opts.dynamicRef ? (0, codegen_1._)`, ${names_1.default.dynamicAnchors}={}` : codegen_1.nil}}={}`;
    }
    function destructureValCxtES5(gen, opts) {
      gen.if(names_1.default.valCxt, () => {
        gen.var(names_1.default.instancePath, (0, codegen_1._)`${names_1.default.valCxt}.${names_1.default.instancePath}`), gen.var(names_1.default.parentData, (0, codegen_1._)`${names_1.default.valCxt}.${names_1.default.parentData}`), gen.var(names_1.default.parentDataProperty, (0, codegen_1._)`${names_1.default.valCxt}.${names_1.default.parentDataProperty}`), gen.var(names_1.default.rootData, (0, codegen_1._)`${names_1.default.valCxt}.${names_1.default.rootData}`), opts.dynamicRef && gen.var(names_1.default.dynamicAnchors, (0, codegen_1._)`${names_1.default.valCxt}.${names_1.default.dynamicAnchors}`);
      }, () => {
        gen.var(names_1.default.instancePath, (0, codegen_1._)`""`), gen.var(names_1.default.parentData, (0, codegen_1._)`undefined`), gen.var(names_1.default.parentDataProperty, (0, codegen_1._)`undefined`), gen.var(names_1.default.rootData, names_1.default.data), opts.dynamicRef && gen.var(names_1.default.dynamicAnchors, (0, codegen_1._)`{}`);
      });
    }
    function topSchemaObjCode(it) {
      let { schema, opts, gen } = it;
      validateFunction(it, () => {
        opts.$comment && schema.$comment && commentKeyword(it), checkNoDefault(it), gen.let(names_1.default.vErrors, null), gen.let(names_1.default.errors, 0), opts.unevaluated && resetEvaluated(it), typeAndKeywords(it), returnResults(it);
      });
    }
    function resetEvaluated(it) {
      let { gen, validateName } = it;
      it.evaluated = gen.const("evaluated", (0, codegen_1._)`${validateName}.evaluated`), gen.if((0, codegen_1._)`${it.evaluated}.dynamicProps`, () => gen.assign((0, codegen_1._)`${it.evaluated}.props`, (0, codegen_1._)`undefined`)), gen.if((0, codegen_1._)`${it.evaluated}.dynamicItems`, () => gen.assign((0, codegen_1._)`${it.evaluated}.items`, (0, codegen_1._)`undefined`));
    }
    function funcSourceUrl(schema, opts) {
      let schId = typeof schema == "object" && schema[opts.schemaId];
      return schId && (opts.code.source || opts.code.process) ? (0, codegen_1._)`/*# sourceURL=${schId} */` : codegen_1.nil;
    }
    function subschemaCode(it, valid) {
      if (isSchemaObj(it) && (checkKeywords(it), schemaCxtHasRules(it))) {
        subSchemaObjCode(it, valid);
        return;
      }
      (0, boolSchema_1.boolOrEmptySchema)(it, valid);
    }
    function schemaCxtHasRules({ schema, self }) {
      if (typeof schema == "boolean")
        return !schema;
      for (let key in schema)
        if (self.RULES.all[key])
          return !0;
      return !1;
    }
    function isSchemaObj(it) {
      return typeof it.schema != "boolean";
    }
    function subSchemaObjCode(it, valid) {
      let { schema, gen, opts } = it;
      opts.$comment && schema.$comment && commentKeyword(it), updateContext(it), checkAsyncSchema(it);
      let errsCount = gen.const("_errs", names_1.default.errors);
      typeAndKeywords(it, errsCount), gen.var(valid, (0, codegen_1._)`${errsCount} === ${names_1.default.errors}`);
    }
    function checkKeywords(it) {
      (0, util_1.checkUnknownRules)(it), checkRefsAndKeywords(it);
    }
    function typeAndKeywords(it, errsCount) {
      if (it.opts.jtd)
        return schemaKeywords(it, [], !1, errsCount);
      let types = (0, dataType_1.getSchemaTypes)(it.schema), checkedTypes = (0, dataType_1.coerceAndCheckDataType)(it, types);
      schemaKeywords(it, types, !checkedTypes, errsCount);
    }
    function checkRefsAndKeywords(it) {
      let { schema, errSchemaPath, opts, self } = it;
      schema.$ref && opts.ignoreKeywordsWithRef && (0, util_1.schemaHasRulesButRef)(schema, self.RULES) && self.logger.warn(`$ref: keywords ignored in schema at path "${errSchemaPath}"`);
    }
    function checkNoDefault(it) {
      let { schema, opts } = it;
      schema.default !== void 0 && opts.useDefaults && opts.strictSchema && (0, util_1.checkStrictMode)(it, "default is ignored in the schema root");
    }
    function updateContext(it) {
      let schId = it.schema[it.opts.schemaId];
      schId && (it.baseId = (0, resolve_1.resolveUrl)(it.opts.uriResolver, it.baseId, schId));
    }
    function checkAsyncSchema(it) {
      if (it.schema.$async && !it.schemaEnv.$async)
        throw new Error("async schema in sync schema");
    }
    function commentKeyword({ gen, schemaEnv, schema, errSchemaPath, opts }) {
      let msg = schema.$comment;
      if (opts.$comment === !0)
        gen.code((0, codegen_1._)`${names_1.default.self}.logger.log(${msg})`);
      else if (typeof opts.$comment == "function") {
        let schemaPath = (0, codegen_1.str)`${errSchemaPath}/$comment`, rootName = gen.scopeValue("root", { ref: schemaEnv.root });
        gen.code((0, codegen_1._)`${names_1.default.self}.opts.$comment(${msg}, ${schemaPath}, ${rootName}.schema)`);
      }
    }
    function returnResults(it) {
      let { gen, schemaEnv, validateName, ValidationError, opts } = it;
      schemaEnv.$async ? gen.if((0, codegen_1._)`${names_1.default.errors} === 0`, () => gen.return(names_1.default.data), () => gen.throw((0, codegen_1._)`new ${ValidationError}(${names_1.default.vErrors})`)) : (gen.assign((0, codegen_1._)`${validateName}.errors`, names_1.default.vErrors), opts.unevaluated && assignEvaluated(it), gen.return((0, codegen_1._)`${names_1.default.errors} === 0`));
    }
    function assignEvaluated({ gen, evaluated, props, items }) {
      props instanceof codegen_1.Name && gen.assign((0, codegen_1._)`${evaluated}.props`, props), items instanceof codegen_1.Name && gen.assign((0, codegen_1._)`${evaluated}.items`, items);
    }
    function schemaKeywords(it, types, typeErrors, errsCount) {
      let { gen, schema, data, allErrors, opts, self } = it, { RULES } = self;
      if (schema.$ref && (opts.ignoreKeywordsWithRef || !(0, util_1.schemaHasRulesButRef)(schema, RULES))) {
        gen.block(() => keywordCode(it, "$ref", RULES.all.$ref.definition));
        return;
      }
      opts.jtd || checkStrictTypes(it, types), gen.block(() => {
        for (let group of RULES.rules)
          groupKeywords(group);
        groupKeywords(RULES.post);
      });
      function groupKeywords(group) {
        (0, applicability_1.shouldUseGroup)(schema, group) && (group.type ? (gen.if((0, dataType_2.checkDataType)(group.type, data, opts.strictNumbers)), iterateKeywords(it, group), types.length === 1 && types[0] === group.type && typeErrors && (gen.else(), (0, dataType_2.reportTypeError)(it)), gen.endIf()) : iterateKeywords(it, group), allErrors || gen.if((0, codegen_1._)`${names_1.default.errors} === ${errsCount || 0}`));
      }
    }
    function iterateKeywords(it, group) {
      let { gen, schema, opts: { useDefaults } } = it;
      useDefaults && (0, defaults_1.assignDefaults)(it, group.type), gen.block(() => {
        for (let rule of group.rules)
          (0, applicability_1.shouldUseRule)(schema, rule) && keywordCode(it, rule.keyword, rule.definition, group.type);
      });
    }
    function checkStrictTypes(it, types) {
      it.schemaEnv.meta || !it.opts.strictTypes || (checkContextTypes(it, types), it.opts.allowUnionTypes || checkMultipleTypes(it, types), checkKeywordTypes(it, it.dataTypes));
    }
    function checkContextTypes(it, types) {
      if (types.length) {
        if (!it.dataTypes.length) {
          it.dataTypes = types;
          return;
        }
        types.forEach((t) => {
          includesType(it.dataTypes, t) || strictTypesError(it, `type "${t}" not allowed by context "${it.dataTypes.join(",")}"`);
        }), narrowSchemaTypes(it, types);
      }
    }
    function checkMultipleTypes(it, ts) {
      ts.length > 1 && !(ts.length === 2 && ts.includes("null")) && strictTypesError(it, "use allowUnionTypes to allow union type keyword");
    }
    function checkKeywordTypes(it, ts) {
      let rules = it.self.RULES.all;
      for (let keyword in rules) {
        let rule = rules[keyword];
        if (typeof rule == "object" && (0, applicability_1.shouldUseRule)(it.schema, rule)) {
          let { type } = rule.definition;
          type.length && !type.some((t) => hasApplicableType(ts, t)) && strictTypesError(it, `missing type "${type.join(",")}" for keyword "${keyword}"`);
        }
      }
    }
    function hasApplicableType(schTs, kwdT) {
      return schTs.includes(kwdT) || kwdT === "number" && schTs.includes("integer");
    }
    function includesType(ts, t) {
      return ts.includes(t) || t === "integer" && ts.includes("number");
    }
    function narrowSchemaTypes(it, withTypes) {
      let ts = [];
      for (let t of it.dataTypes)
        includesType(withTypes, t) ? ts.push(t) : withTypes.includes("integer") && t === "number" && ts.push("integer");
      it.dataTypes = ts;
    }
    function strictTypesError(it, msg) {
      let schemaPath = it.schemaEnv.baseId + it.errSchemaPath;
      msg += ` at "${schemaPath}" (strictTypes)`, (0, util_1.checkStrictMode)(it, msg, it.opts.strictTypes);
    }
    var KeywordCxt = class {
      constructor(it, def, keyword) {
        if ((0, keyword_1.validateKeywordUsage)(it, def, keyword), this.gen = it.gen, this.allErrors = it.allErrors, this.keyword = keyword, this.data = it.data, this.schema = it.schema[keyword], this.$data = def.$data && it.opts.$data && this.schema && this.schema.$data, this.schemaValue = (0, util_1.schemaRefOrVal)(it, this.schema, keyword, this.$data), this.schemaType = def.schemaType, this.parentSchema = it.schema, this.params = {}, this.it = it, this.def = def, this.$data)
          this.schemaCode = it.gen.const("vSchema", getData(this.$data, it));
        else if (this.schemaCode = this.schemaValue, !(0, keyword_1.validSchemaType)(this.schema, def.schemaType, def.allowUndefined))
          throw new Error(`${keyword} value must be ${JSON.stringify(def.schemaType)}`);
        ("code" in def ? def.trackErrors : def.errors !== !1) && (this.errsCount = it.gen.const("_errs", names_1.default.errors));
      }
      result(condition, successAction, failAction) {
        this.failResult((0, codegen_1.not)(condition), successAction, failAction);
      }
      failResult(condition, successAction, failAction) {
        this.gen.if(condition), failAction ? failAction() : this.error(), successAction ? (this.gen.else(), successAction(), this.allErrors && this.gen.endIf()) : this.allErrors ? this.gen.endIf() : this.gen.else();
      }
      pass(condition, failAction) {
        this.failResult((0, codegen_1.not)(condition), void 0, failAction);
      }
      fail(condition) {
        if (condition === void 0) {
          this.error(), this.allErrors || this.gen.if(!1);
          return;
        }
        this.gen.if(condition), this.error(), this.allErrors ? this.gen.endIf() : this.gen.else();
      }
      fail$data(condition) {
        if (!this.$data)
          return this.fail(condition);
        let { schemaCode } = this;
        this.fail((0, codegen_1._)`${schemaCode} !== undefined && (${(0, codegen_1.or)(this.invalid$data(), condition)})`);
      }
      error(append, errorParams, errorPaths) {
        if (errorParams) {
          this.setParams(errorParams), this._error(append, errorPaths), this.setParams({});
          return;
        }
        this._error(append, errorPaths);
      }
      _error(append, errorPaths) {
        (append ? errors_1.reportExtraError : errors_1.reportError)(this, this.def.error, errorPaths);
      }
      $dataError() {
        (0, errors_1.reportError)(this, this.def.$dataError || errors_1.keyword$DataError);
      }
      reset() {
        if (this.errsCount === void 0)
          throw new Error('add "trackErrors" to keyword definition');
        (0, errors_1.resetErrorsCount)(this.gen, this.errsCount);
      }
      ok(cond) {
        this.allErrors || this.gen.if(cond);
      }
      setParams(obj, assign) {
        assign ? Object.assign(this.params, obj) : this.params = obj;
      }
      block$data(valid, codeBlock, $dataValid = codegen_1.nil) {
        this.gen.block(() => {
          this.check$data(valid, $dataValid), codeBlock();
        });
      }
      check$data(valid = codegen_1.nil, $dataValid = codegen_1.nil) {
        if (!this.$data)
          return;
        let { gen, schemaCode, schemaType, def } = this;
        gen.if((0, codegen_1.or)((0, codegen_1._)`${schemaCode} === undefined`, $dataValid)), valid !== codegen_1.nil && gen.assign(valid, !0), (schemaType.length || def.validateSchema) && (gen.elseIf(this.invalid$data()), this.$dataError(), valid !== codegen_1.nil && gen.assign(valid, !1)), gen.else();
      }
      invalid$data() {
        let { gen, schemaCode, schemaType, def, it } = this;
        return (0, codegen_1.or)(wrong$DataType(), invalid$DataSchema());
        function wrong$DataType() {
          if (schemaType.length) {
            if (!(schemaCode instanceof codegen_1.Name))
              throw new Error("ajv implementation error");
            let st = Array.isArray(schemaType) ? schemaType : [schemaType];
            return (0, codegen_1._)`${(0, dataType_2.checkDataTypes)(st, schemaCode, it.opts.strictNumbers, dataType_2.DataType.Wrong)}`;
          }
          return codegen_1.nil;
        }
        function invalid$DataSchema() {
          if (def.validateSchema) {
            let validateSchemaRef = gen.scopeValue("validate$data", { ref: def.validateSchema });
            return (0, codegen_1._)`!${validateSchemaRef}(${schemaCode})`;
          }
          return codegen_1.nil;
        }
      }
      subschema(appl, valid) {
        let subschema = (0, subschema_1.getSubschema)(this.it, appl);
        (0, subschema_1.extendSubschemaData)(subschema, this.it, appl), (0, subschema_1.extendSubschemaMode)(subschema, appl);
        let nextContext = { ...this.it, ...subschema, items: void 0, props: void 0 };
        return subschemaCode(nextContext, valid), nextContext;
      }
      mergeEvaluated(schemaCxt, toName) {
        let { it, gen } = this;
        it.opts.unevaluated && (it.props !== !0 && schemaCxt.props !== void 0 && (it.props = util_1.mergeEvaluated.props(gen, schemaCxt.props, it.props, toName)), it.items !== !0 && schemaCxt.items !== void 0 && (it.items = util_1.mergeEvaluated.items(gen, schemaCxt.items, it.items, toName)));
      }
      mergeValidEvaluated(schemaCxt, valid) {
        let { it, gen } = this;
        if (it.opts.unevaluated && (it.props !== !0 || it.items !== !0))
          return gen.if(valid, () => this.mergeEvaluated(schemaCxt, codegen_1.Name)), !0;
      }
    };
    exports.KeywordCxt = KeywordCxt;
    function keywordCode(it, keyword, def, ruleType) {
      let cxt = new KeywordCxt(it, def, keyword);
      "code" in def ? def.code(cxt, ruleType) : cxt.$data && def.validate ? (0, keyword_1.funcKeywordCode)(cxt, def) : "macro" in def ? (0, keyword_1.macroKeywordCode)(cxt, def) : (def.compile || def.validate) && (0, keyword_1.funcKeywordCode)(cxt, def);
    }
    var JSON_POINTER = /^\/(?:[^~]|~0|~1)*$/, RELATIVE_JSON_POINTER = /^([0-9]+)(#|\/(?:[^~]|~0|~1)*)?$/;
    function getData($data, { dataLevel, dataNames, dataPathArr }) {
      let jsonPointer, data;
      if ($data === "")
        return names_1.default.rootData;
      if ($data[0] === "/") {
        if (!JSON_POINTER.test($data))
          throw new Error(`Invalid JSON-pointer: ${$data}`);
        jsonPointer = $data, data = names_1.default.rootData;
      } else {
        let matches = RELATIVE_JSON_POINTER.exec($data);
        if (!matches)
          throw new Error(`Invalid JSON-pointer: ${$data}`);
        let up = +matches[1];
        if (jsonPointer = matches[2], jsonPointer === "#") {
          if (up >= dataLevel)
            throw new Error(errorMsg("property/index", up));
          return dataPathArr[dataLevel - up];
        }
        if (up > dataLevel)
          throw new Error(errorMsg("data", up));
        if (data = dataNames[dataLevel - up], !jsonPointer)
          return data;
      }
      let expr = data, segments = jsonPointer.split("/");
      for (let segment of segments)
        segment && (data = (0, codegen_1._)`${data}${(0, codegen_1.getProperty)((0, util_1.unescapeJsonPointer)(segment))}`, expr = (0, codegen_1._)`${expr} && ${data}`);
      return expr;
      function errorMsg(pointerType, up) {
        return `Cannot access ${pointerType} ${up} levels up, current level is ${dataLevel}`;
      }
    }
    exports.getData = getData;
  }
});

// node_modules/ajv/dist/runtime/validation_error.js
var require_validation_error = __commonJS({
  "node_modules/ajv/dist/runtime/validation_error.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var ValidationError = class extends Error {
      constructor(errors) {
        super("validation failed"), this.errors = errors, this.ajv = this.validation = !0;
      }
    };
    exports.default = ValidationError;
  }
});

// node_modules/ajv/dist/compile/ref_error.js
var require_ref_error = __commonJS({
  "node_modules/ajv/dist/compile/ref_error.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var resolve_1 = require_resolve(), MissingRefError = class extends Error {
      constructor(resolver, baseId, ref2, msg) {
        super(msg || `can't resolve reference ${ref2} from id ${baseId}`), this.missingRef = (0, resolve_1.resolveUrl)(resolver, baseId, ref2), this.missingSchema = (0, resolve_1.normalizeId)((0, resolve_1.getFullPath)(resolver, this.missingRef));
      }
    };
    exports.default = MissingRefError;
  }
});

// node_modules/ajv/dist/compile/index.js
var require_compile = __commonJS({
  "node_modules/ajv/dist/compile/index.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.resolveSchema = exports.getCompilingSchema = exports.resolveRef = exports.compileSchema = exports.SchemaEnv = void 0;
    var codegen_1 = require_codegen(), validation_error_1 = require_validation_error(), names_1 = require_names(), resolve_1 = require_resolve(), util_1 = require_util(), validate_1 = require_validate(), SchemaEnv = class {
      constructor(env2) {
        var _a;
        this.refs = {}, this.dynamicAnchors = {};
        let schema;
        typeof env2.schema == "object" && (schema = env2.schema), this.schema = env2.schema, this.schemaId = env2.schemaId, this.root = env2.root || this, this.baseId = (_a = env2.baseId) !== null && _a !== void 0 ? _a : (0, resolve_1.normalizeId)(schema?.[env2.schemaId || "$id"]), this.schemaPath = env2.schemaPath, this.localRefs = env2.localRefs, this.meta = env2.meta, this.$async = schema?.$async, this.refs = {};
      }
    };
    exports.SchemaEnv = SchemaEnv;
    function compileSchema(sch) {
      let _sch = getCompilingSchema.call(this, sch);
      if (_sch)
        return _sch;
      let rootId = (0, resolve_1.getFullPath)(this.opts.uriResolver, sch.root.baseId), { es5, lines } = this.opts.code, { ownProperties } = this.opts, gen = new codegen_1.CodeGen(this.scope, { es5, lines, ownProperties }), _ValidationError;
      sch.$async && (_ValidationError = gen.scopeValue("Error", {
        ref: validation_error_1.default,
        code: (0, codegen_1._)`require("ajv/dist/runtime/validation_error").default`
      }));
      let validateName = gen.scopeName("validate");
      sch.validateName = validateName;
      let schemaCxt = {
        gen,
        allErrors: this.opts.allErrors,
        data: names_1.default.data,
        parentData: names_1.default.parentData,
        parentDataProperty: names_1.default.parentDataProperty,
        dataNames: [names_1.default.data],
        dataPathArr: [codegen_1.nil],
        // TODO can its length be used as dataLevel if nil is removed?
        dataLevel: 0,
        dataTypes: [],
        definedProperties: /* @__PURE__ */ new Set(),
        topSchemaRef: gen.scopeValue("schema", this.opts.code.source === !0 ? { ref: sch.schema, code: (0, codegen_1.stringify)(sch.schema) } : { ref: sch.schema }),
        validateName,
        ValidationError: _ValidationError,
        schema: sch.schema,
        schemaEnv: sch,
        rootId,
        baseId: sch.baseId || rootId,
        schemaPath: codegen_1.nil,
        errSchemaPath: sch.schemaPath || (this.opts.jtd ? "" : "#"),
        errorPath: (0, codegen_1._)`""`,
        opts: this.opts,
        self: this
      }, sourceCode;
      try {
        this._compilations.add(sch), (0, validate_1.validateFunctionCode)(schemaCxt), gen.optimize(this.opts.code.optimize);
        let validateCode = gen.toString();
        sourceCode = `${gen.scopeRefs(names_1.default.scope)}return ${validateCode}`, this.opts.code.process && (sourceCode = this.opts.code.process(sourceCode, sch));
        let validate = new Function(`${names_1.default.self}`, `${names_1.default.scope}`, sourceCode)(this, this.scope.get());
        if (this.scope.value(validateName, { ref: validate }), validate.errors = null, validate.schema = sch.schema, validate.schemaEnv = sch, sch.$async && (validate.$async = !0), this.opts.code.source === !0 && (validate.source = { validateName, validateCode, scopeValues: gen._values }), this.opts.unevaluated) {
          let { props, items } = schemaCxt;
          validate.evaluated = {
            props: props instanceof codegen_1.Name ? void 0 : props,
            items: items instanceof codegen_1.Name ? void 0 : items,
            dynamicProps: props instanceof codegen_1.Name,
            dynamicItems: items instanceof codegen_1.Name
          }, validate.source && (validate.source.evaluated = (0, codegen_1.stringify)(validate.evaluated));
        }
        return sch.validate = validate, sch;
      } catch (e) {
        throw delete sch.validate, delete sch.validateName, sourceCode && this.logger.error("Error compiling schema, function code:", sourceCode), e;
      } finally {
        this._compilations.delete(sch);
      }
    }
    exports.compileSchema = compileSchema;
    function resolveRef(root, baseId, ref2) {
      var _a;
      ref2 = (0, resolve_1.resolveUrl)(this.opts.uriResolver, baseId, ref2);
      let schOrFunc = root.refs[ref2];
      if (schOrFunc)
        return schOrFunc;
      let _sch = resolve.call(this, root, ref2);
      if (_sch === void 0) {
        let schema = (_a = root.localRefs) === null || _a === void 0 ? void 0 : _a[ref2], { schemaId } = this.opts;
        schema && (_sch = new SchemaEnv({ schema, schemaId, root, baseId }));
      }
      if (_sch !== void 0)
        return root.refs[ref2] = inlineOrCompile.call(this, _sch);
    }
    exports.resolveRef = resolveRef;
    function inlineOrCompile(sch) {
      return (0, resolve_1.inlineRef)(sch.schema, this.opts.inlineRefs) ? sch.schema : sch.validate ? sch : compileSchema.call(this, sch);
    }
    function getCompilingSchema(schEnv) {
      for (let sch of this._compilations)
        if (sameSchemaEnv(sch, schEnv))
          return sch;
    }
    exports.getCompilingSchema = getCompilingSchema;
    function sameSchemaEnv(s1, s2) {
      return s1.schema === s2.schema && s1.root === s2.root && s1.baseId === s2.baseId;
    }
    function resolve(root, ref2) {
      let sch;
      for (; typeof (sch = this.refs[ref2]) == "string"; )
        ref2 = sch;
      return sch || this.schemas[ref2] || resolveSchema.call(this, root, ref2);
    }
    function resolveSchema(root, ref2) {
      let p = this.opts.uriResolver.parse(ref2), refPath = (0, resolve_1._getFullPath)(this.opts.uriResolver, p), baseId = (0, resolve_1.getFullPath)(this.opts.uriResolver, root.baseId, void 0);
      if (Object.keys(root.schema).length > 0 && refPath === baseId)
        return getJsonPointer.call(this, p, root);
      let id = (0, resolve_1.normalizeId)(refPath), schOrRef = this.refs[id] || this.schemas[id];
      if (typeof schOrRef == "string") {
        let sch = resolveSchema.call(this, root, schOrRef);
        return typeof sch?.schema != "object" ? void 0 : getJsonPointer.call(this, p, sch);
      }
      if (typeof schOrRef?.schema == "object") {
        if (schOrRef.validate || compileSchema.call(this, schOrRef), id === (0, resolve_1.normalizeId)(ref2)) {
          let { schema } = schOrRef, { schemaId } = this.opts, schId = schema[schemaId];
          return schId && (baseId = (0, resolve_1.resolveUrl)(this.opts.uriResolver, baseId, schId)), new SchemaEnv({ schema, schemaId, root, baseId });
        }
        return getJsonPointer.call(this, p, schOrRef);
      }
    }
    exports.resolveSchema = resolveSchema;
    var PREVENT_SCOPE_CHANGE = /* @__PURE__ */ new Set([
      "properties",
      "patternProperties",
      "enum",
      "dependencies",
      "definitions"
    ]);
    function getJsonPointer(parsedRef, { baseId, schema, root }) {
      var _a;
      if (((_a = parsedRef.fragment) === null || _a === void 0 ? void 0 : _a[0]) !== "/")
        return;
      for (let part of parsedRef.fragment.slice(1).split("/")) {
        if (typeof schema == "boolean")
          return;
        let partSchema = schema[(0, util_1.unescapeFragment)(part)];
        if (partSchema === void 0)
          return;
        schema = partSchema;
        let schId = typeof schema == "object" && schema[this.opts.schemaId];
        !PREVENT_SCOPE_CHANGE.has(part) && schId && (baseId = (0, resolve_1.resolveUrl)(this.opts.uriResolver, baseId, schId));
      }
      let env2;
      if (typeof schema != "boolean" && schema.$ref && !(0, util_1.schemaHasRulesButRef)(schema, this.RULES)) {
        let $ref = (0, resolve_1.resolveUrl)(this.opts.uriResolver, baseId, schema.$ref);
        env2 = resolveSchema.call(this, root, $ref);
      }
      let { schemaId } = this.opts;
      if (env2 = env2 || new SchemaEnv({ schema, schemaId, root, baseId }), env2.schema !== env2.root.schema)
        return env2;
    }
  }
});

// node_modules/ajv/dist/refs/data.json
var require_data = __commonJS({
  "node_modules/ajv/dist/refs/data.json"(exports, module) {
    module.exports = {
      $id: "https://raw.githubusercontent.com/ajv-validator/ajv/master/lib/refs/data.json#",
      description: "Meta-schema for $data reference (JSON AnySchema extension proposal)",
      type: "object",
      required: ["$data"],
      properties: {
        $data: {
          type: "string",
          anyOf: [{ format: "relative-json-pointer" }, { format: "json-pointer" }]
        }
      },
      additionalProperties: !1
    };
  }
});

// node_modules/fast-uri/lib/utils.js
var require_utils = __commonJS({
  "node_modules/fast-uri/lib/utils.js"(exports, module) {
    "use strict";
    var isUUID = RegExp.prototype.test.bind(/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/iu), isIPv4 = RegExp.prototype.test.bind(/^(?:(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]\d|\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d{2}|[1-9]\d|\d)$/u), isPort = RegExp.prototype.test.bind(/^\d*$/u), isHexPair = RegExp.prototype.test.bind(/^[\da-f]{2}$/iu), isUnreserved = RegExp.prototype.test.bind(/^[\da-z\-._~]$/iu), isPathCharacter = RegExp.prototype.test.bind(/^[A-Za-z0-9\-._~!$&'()*+,;=:@/]$/u), isQueryFragmentCharacter = RegExp.prototype.test.bind(/^[A-Za-z0-9\-._~!$&'()*+,;=:@/?]$/u), isUserinfoCharacter = RegExp.prototype.test.bind(/^[A-Za-z0-9\-._~!$&'()*+,;=:]$/u), BYTE_HEX = new Array(256);
    {
      let HEX_DIGITS = "0123456789ABCDEF";
      for (let i = 0; i < 256; i++)
        BYTE_HEX[i] = "%" + HEX_DIGITS[i >> 4] + HEX_DIGITS[i & 15];
    }
    function percentEncodeNonAscii(cp) {
      return cp < 2048 ? BYTE_HEX[192 | cp >> 6] + BYTE_HEX[128 | cp & 63] : cp < 65536 ? BYTE_HEX[224 | cp >> 12] + BYTE_HEX[128 | cp >> 6 & 63] + BYTE_HEX[128 | cp & 63] : BYTE_HEX[240 | cp >> 18] + BYTE_HEX[128 | cp >> 12 & 63] + BYTE_HEX[128 | cp >> 6 & 63] + BYTE_HEX[128 | cp & 63];
    }
    function stringArrayToHexStripped(input) {
      let acc = "", code = 0, i = 0;
      for (i = 0; i < input.length; i++)
        if (code = input[i].charCodeAt(0), code !== 48) {
          if (!(code >= 48 && code <= 57 || code >= 65 && code <= 70 || code >= 97 && code <= 102))
            return "";
          acc += input[i];
          break;
        }
      for (i += 1; i < input.length; i++) {
        if (code = input[i].charCodeAt(0), !(code >= 48 && code <= 57 || code >= 65 && code <= 70 || code >= 97 && code <= 102))
          return "";
        acc += input[i];
      }
      return acc;
    }
    var isHextet = RegExp.prototype.test.bind(/^[\dA-Fa-f]{1,4}$/), isIPvFuture = RegExp.prototype.test.bind(/^[vV][\dA-Fa-f]+\.[A-Za-z\d\-._~!$&'()*+,;=:]+$/), isZoneCharacter = RegExp.prototype.test.bind(/^[A-Za-z\d\-._~]$/), nonSimpleDomain = RegExp.prototype.test.bind(/[^!"$&'()*+,\-.;=_`a-z{}~]/u);
    function isZoneIdentifier(zone) {
      if (zone.length === 0) return !1;
      for (let i = 0; i < zone.length; i++)
        if (!isZoneCharacter(zone[i])) {
          if (zone[i] === "%" && i + 2 < zone.length && isHexPair(zone.slice(i + 1, i + 3))) {
            i += 2;
            continue;
          }
          return !1;
        }
      return !0;
    }
    function compressIPv6ZeroRun(hextets) {
      let bestStart = -1, bestLength = 0, runStart = -1, runLength = 0;
      for (let i = 0; i < hextets.length; i++)
        hextets[i] === "0" ? (runStart === -1 && (runStart = i), runLength++, runLength > bestLength && (bestLength = runLength, bestStart = runStart)) : (runStart = -1, runLength = 0);
      if (bestLength < 2) return hextets.join(":");
      let head = hextets.slice(0, bestStart).join(":"), tail = hextets.slice(bestStart + bestLength).join(":");
      return head + "::" + tail;
    }
    function normalizeIPv6Address(input) {
      let compression = input.indexOf("::");
      if (compression !== -1 && input.indexOf("::", compression + 1) !== -1) return;
      let left = compression === -1 ? input.split(":") : input.slice(0, compression).split(":"), right = compression === -1 ? [] : input.slice(compression + 2).split(":");
      compression !== -1 && (left.length === 1 && left[0] === "" && (left.length = 0), right.length === 1 && right[0] === "" && (right.length = 0));
      let parts = left.concat(right), hextetCount = 0;
      for (let i = 0; i < parts.length; i++) {
        let part = parts[i];
        if (part === "") return;
        if (part.indexOf(".") !== -1) {
          if (i !== parts.length - 1 || compression !== -1 && right.length === 0 || !isIPv4(part)) return;
          hextetCount += 2;
          continue;
        }
        if (!isHextet(part)) return;
        parts[i] = parseInt(part, 16).toString(16), hextetCount++;
      }
      if (compression === -1)
        return hextetCount !== 8 ? void 0 : compressIPv6ZeroRun(parts);
      if (hextetCount >= 8) return;
      let expanded = parts.slice(0, left.length);
      for (let i = hextetCount; i < 8; i++) expanded.push("0");
      for (let i = left.length; i < parts.length; i++) expanded.push(parts[i]);
      return compressIPv6ZeroRun(expanded);
    }
    function normalizeIPv6(host) {
      let bracketed = host[0] === "[" && host[host.length - 1] === "]";
      if ((host[0] === "[" || host[host.length - 1] === "]") && !bracketed) return { host, isIPV6: !1, error: !0 };
      let input = bracketed ? host.slice(1, -1) : host;
      if (bracketed && isIPvFuture(input))
        return input = input.toLowerCase(), { host: `[${input}]`, escapedHost: input, isIPV6: !1, isIPVFuture: !0 };
      if (findToken(input, ":") < 2)
        return { host, isIPV6: !1, error: bracketed };
      let zoneIdentifier = "", zoneSeparator = input.indexOf("%");
      if (zoneSeparator !== -1) {
        let separatorLength = input.slice(zoneSeparator, zoneSeparator + 3).toLowerCase() === "%25" ? 3 : 1;
        if (zoneIdentifier = input.slice(zoneSeparator + separatorLength), !isZoneIdentifier(zoneIdentifier)) return { host, isIPV6: !1, error: !0 };
        input = input.slice(0, zoneSeparator);
      }
      let address = normalizeIPv6Address(input);
      return address === void 0 ? { host, isIPV6: !1, error: !0 } : {
        host: address + (zoneIdentifier ? "%" + zoneIdentifier : ""),
        escapedHost: address + (zoneIdentifier ? "%25" + zoneIdentifier : ""),
        isIPV6: !0
      };
    }
    function findToken(str, token) {
      let ind = 0;
      for (let i = 0; i < str.length; i++)
        str[i] === token && ind++;
      return ind;
    }
    function removeDotSegments(path3) {
      let input = path3, output = [], nextSlash = -1, len = 0;
      for (; len = input.length; ) {
        if (len === 1) {
          if (input === ".")
            break;
          if (input === "/") {
            output.push("/");
            break;
          } else {
            output.push(input);
            break;
          }
        } else if (len === 2) {
          if (input[0] === ".") {
            if (input[1] === ".")
              break;
            if (input[1] === "/") {
              input = input.slice(2);
              continue;
            }
          } else if (input[0] === "/" && (input[1] === "." || input[1] === "/")) {
            output.push("/");
            break;
          }
        } else if (len === 3 && input === "/..") {
          output.length !== 0 && output.pop(), output.push("/");
          break;
        }
        if (input[0] === ".") {
          if (input[1] === ".") {
            if (input[2] === "/") {
              input = input.slice(3);
              continue;
            }
          } else if (input[1] === "/") {
            input = input.slice(2);
            continue;
          }
        } else if (input[0] === "/" && input[1] === ".") {
          if (input[2] === "/") {
            input = input.slice(2);
            continue;
          } else if (input[2] === "." && input[3] === "/") {
            input = input.slice(3), output.length !== 0 && output.pop();
            continue;
          }
        }
        if ((nextSlash = input.indexOf("/", 1)) === -1) {
          output.push(input);
          break;
        } else
          output.push(input.slice(0, nextSlash)), input = input.slice(nextSlash);
      }
      return output.join("");
    }
    function removeDotSegmentsRelative(path3) {
      if (path3 === "" || path3[0] === "/")
        return removeDotSegments(path3);
      let segments = path3.split("/"), last = segments.length - 1, output = [], trailingDirectory = !1;
      for (let i = 0; i <= last; i++) {
        let segment = segments[i];
        segment === "." || segment === ".." ? (segment === ".." && (output.length !== 0 && output[output.length - 1] !== ".." ? output.pop() : output.push("..")), trailingDirectory = i === last) : output.push(segment);
      }
      return trailingDirectory && output[output.length - 1] !== ".." && output.push(""), (output.length === 0 || output[0] === "" || output[0].includes(":")) && output.unshift("."), output.join("/");
    }
    var HOST_DELIMS = { "@": "%40", "/": "%2F", "?": "%3F", "#": "%23", ":": "%3A" }, HOST_DELIM_RE = /[@/?#:]/g, HOST_DELIM_NO_COLON_RE = /[@/?#]/g;
    function reescapeHostDelimiters(host, isIP) {
      let re = isIP ? HOST_DELIM_NO_COLON_RE : HOST_DELIM_RE;
      return re.lastIndex = 0, host.replace(re, (ch) => HOST_DELIMS[ch]);
    }
    function normalizePercentEncoding(input, decodeUnreserved = !1) {
      if (input.indexOf("%") === -1)
        return input;
      let output = "";
      for (let i = 0; i < input.length; i++) {
        if (input[i] === "%" && i + 2 < input.length) {
          let hex = input.slice(i + 1, i + 3);
          if (isHexPair(hex)) {
            let normalizedHex = hex.toUpperCase(), decoded = String.fromCharCode(parseInt(normalizedHex, 16));
            decodeUnreserved && isUnreserved(decoded) ? output += decoded : output += "%" + normalizedHex, i += 2;
            continue;
          }
        }
        output += input[i];
      }
      return output;
    }
    function normalizePathEncoding(input) {
      let output = "";
      for (let i = 0; i < input.length; i++) {
        let ch = input[i];
        if (ch === "%" && i + 2 < input.length) {
          let hex = input.slice(i + 1, i + 3);
          if (isHexPair(hex)) {
            let normalizedHex = hex.toUpperCase(), decoded = String.fromCharCode(parseInt(normalizedHex, 16));
            decoded !== "." && isUnreserved(decoded) ? output += decoded : output += "%" + normalizedHex, i += 2;
            continue;
          }
        }
        if (isPathCharacter(ch))
          output += ch;
        else {
          let code = input.charCodeAt(i);
          if (code < 128)
            output += isEscapeSafe(code) ? ch : BYTE_HEX[code];
          else if (code < 55296 || code > 57343)
            output += percentEncodeNonAscii(code);
          else if (code <= 56319 && i + 1 < input.length) {
            let low = input.charCodeAt(i + 1);
            low >= 56320 && low <= 57343 ? (output += percentEncodeNonAscii(65536 + (code - 55296 << 10) + (low - 56320)), i++) : output += percentEncodeNonAscii(65533);
          } else
            output += percentEncodeNonAscii(65533);
        }
      }
      return output;
    }
    function serializePathEncoding(input, pathNoScheme = !1) {
      let output = "", firstSegment = pathNoScheme && input[0] !== "/";
      for (let i = 0; i < input.length; i++) {
        let ch = input[i];
        if (ch === "%" && i + 2 < input.length) {
          let hex = input.slice(i + 1, i + 3);
          if (isHexPair(hex)) {
            output += "%" + hex.toUpperCase(), i += 2;
            continue;
          }
        }
        if (ch === "/" && (firstSegment = !1), isPathCharacter(ch) && (ch !== ":" || !firstSegment))
          output += ch;
        else {
          let code = input.charCodeAt(i);
          if (code < 128)
            output += BYTE_HEX[code];
          else if (code < 55296 || code > 57343)
            output += percentEncodeNonAscii(code);
          else if (code <= 56319 && i + 1 < input.length) {
            let low = input.charCodeAt(i + 1);
            low >= 56320 && low <= 57343 ? (output += percentEncodeNonAscii(65536 + (code - 55296 << 10) + (low - 56320)), i++) : output += percentEncodeNonAscii(65533);
          } else
            output += percentEncodeNonAscii(65533);
        }
      }
      return output;
    }
    function encodeComponent(input, isAllowed) {
      let output = "";
      for (let i = 0; i < input.length; i++) {
        let ch = input[i];
        if (ch === "%" && i + 2 < input.length) {
          let hex = input.slice(i + 1, i + 3);
          if (isHexPair(hex)) {
            output += "%" + hex.toUpperCase(), i += 2;
            continue;
          }
        }
        if (isAllowed(ch))
          output += ch;
        else {
          let code = input.charCodeAt(i);
          if (code < 128)
            output += BYTE_HEX[code];
          else if (code < 55296 || code > 57343)
            output += percentEncodeNonAscii(code);
          else if (code <= 56319 && i + 1 < input.length) {
            let low = input.charCodeAt(i + 1);
            low >= 56320 && low <= 57343 ? (output += percentEncodeNonAscii(65536 + (code - 55296 << 10) + (low - 56320)), i++) : output += percentEncodeNonAscii(65533);
          } else
            output += percentEncodeNonAscii(65533);
        }
      }
      return output;
    }
    function encodeUserinfo(input) {
      return encodeComponent(input, isUserinfoCharacter);
    }
    function encodeQuery(input) {
      return encodeComponent(input, isQueryFragmentCharacter);
    }
    function encodeFragment(input) {
      return encodeComponent(input, isQueryFragmentCharacter);
    }
    function isEscapeSafe(cp) {
      return cp >= 48 && cp <= 57 || cp >= 65 && cp <= 90 || cp >= 97 && cp <= 122 || cp === 42 || cp === 43 || cp === 45 || cp === 46 || cp === 47 || cp === 64 || cp === 95;
    }
    function normalizeQueryFragmentEncoding(input) {
      let output = "";
      for (let i = 0; i < input.length; i++) {
        let ch = input[i];
        if (ch === "%" && i + 2 < input.length) {
          let hex = input.slice(i + 1, i + 3);
          if (isHexPair(hex)) {
            let normalizedHex = hex.toUpperCase(), decoded = String.fromCharCode(parseInt(normalizedHex, 16));
            isUnreserved(decoded) ? output += decoded : output += "%" + normalizedHex, i += 2;
            continue;
          }
        }
        if (isQueryFragmentCharacter(ch))
          output += ch;
        else {
          let code = input.charCodeAt(i);
          if (code < 128)
            output += isEscapeSafe(code) ? ch : BYTE_HEX[code];
          else if (code < 55296 || code > 57343)
            output += percentEncodeNonAscii(code);
          else if (code <= 56319 && i + 1 < input.length) {
            let low = input.charCodeAt(i + 1);
            low >= 56320 && low <= 57343 ? (output += percentEncodeNonAscii(65536 + (code - 55296 << 10) + (low - 56320)), i++) : output += percentEncodeNonAscii(65533);
          } else
            output += percentEncodeNonAscii(65533);
        }
      }
      return output;
    }
    function escapePreservingEscapes(input) {
      let output = "";
      for (let i = 0; i < input.length; i++) {
        if (input[i] === "%" && i + 2 < input.length) {
          let hex = input.slice(i + 1, i + 3);
          if (isHexPair(hex)) {
            output += "%" + hex.toUpperCase(), i += 2;
            continue;
          }
        }
        output += escape(input[i]);
      }
      return output;
    }
    function recomposeAuthority(component) {
      let uriTokens = [];
      if (component.userinfo !== void 0 && (uriTokens.push(encodeUserinfo(component.userinfo)), uriTokens.push("@")), component.host !== void 0) {
        let host = component.host;
        if (!isIPv4(host)) {
          let ipV6res = normalizeIPv6(host);
          ipV6res.isIPV6 !== !0 && ipV6res.isIPVFuture !== !0 && (host = normalizePercentEncoding(host, !0), ipV6res = normalizeIPv6(host)), ipV6res.isIPV6 === !0 || ipV6res.isIPVFuture === !0 ? host = `[${ipV6res.escapedHost}]` : host = reescapeHostDelimiters(host, !1);
        }
        uriTokens.push(host);
      }
      if (typeof component.port == "number" || typeof component.port == "string") {
        let port = String(component.port);
        if (!isPort(port))
          throw new TypeError("URI port is malformed.");
        uriTokens.push(":"), uriTokens.push(port);
      }
      return uriTokens.length ? uriTokens.join("") : void 0;
    }
    module.exports = {
      nonSimpleDomain,
      recomposeAuthority,
      reescapeHostDelimiters,
      normalizePercentEncoding,
      normalizePathEncoding,
      serializePathEncoding,
      normalizeQueryFragmentEncoding,
      encodeUserinfo,
      encodeQuery,
      encodeFragment,
      escapePreservingEscapes,
      removeDotSegments,
      removeDotSegmentsRelative,
      isIPv4,
      isUUID,
      normalizeIPv6,
      stringArrayToHexStripped
    };
  }
});

// node_modules/fast-uri/lib/schemes.js
var require_schemes = __commonJS({
  "node_modules/fast-uri/lib/schemes.js"(exports, module) {
    "use strict";
    var { isUUID } = require_utils(), URN_REG = /^([\da-z][\d\-a-z]{0,31}):((?:[\w!$'()*+,\-./:;=@]|%[\da-f]{2})+)$/iu, supportedSchemeNames = (
      /** @type {const} */
      [
        "http",
        "https",
        "ws",
        "wss",
        "urn",
        "urn:uuid"
      ]
    );
    function isValidSchemeName(name) {
      return supportedSchemeNames.indexOf(
        /** @type {*} */
        name
      ) !== -1;
    }
    function wsIsSecure(wsComponent) {
      return wsComponent.secure === !0 ? !0 : wsComponent.secure === !1 ? !1 : wsComponent.scheme ? wsComponent.scheme.length === 3 && (wsComponent.scheme[0] === "w" || wsComponent.scheme[0] === "W") && (wsComponent.scheme[1] === "s" || wsComponent.scheme[1] === "S") && (wsComponent.scheme[2] === "s" || wsComponent.scheme[2] === "S") : !1;
    }
    function httpParse(component) {
      return component.host || (component.error = component.error || "HTTP URIs must have a host."), component;
    }
    function httpSerialize(component) {
      let secure = String(component.scheme).toLowerCase() === "https";
      return (component.port === (secure ? 443 : 80) || component.port === "") && (component.port = void 0), component.path || (component.path = "/"), component;
    }
    function wsParse(wsComponent) {
      return wsComponent.secure = wsIsSecure(wsComponent), wsComponent.resourceName = (wsComponent.path || "/") + (wsComponent.query ? "?" + wsComponent.query : ""), wsComponent.path = void 0, wsComponent.query = void 0, wsComponent;
    }
    function wsSerialize(wsComponent) {
      if ((wsComponent.port === (wsIsSecure(wsComponent) ? 443 : 80) || wsComponent.port === "") && (wsComponent.port = void 0), typeof wsComponent.secure == "boolean" && (wsComponent.scheme = wsComponent.secure ? "wss" : "ws", wsComponent.secure = void 0), wsComponent.resourceName) {
        let queryIndex = wsComponent.resourceName.indexOf("?"), path3 = queryIndex === -1 ? wsComponent.resourceName : wsComponent.resourceName.slice(0, queryIndex);
        wsComponent.path = path3 && path3 !== "/" ? path3 : void 0, wsComponent.query = queryIndex === -1 ? void 0 : wsComponent.resourceName.slice(queryIndex + 1), wsComponent.resourceName = void 0;
      }
      return wsComponent.fragment = void 0, wsComponent;
    }
    function urnParse(urnComponent, options) {
      if (!urnComponent.path)
        return urnComponent.error = "URN can not be parsed", urnComponent;
      let matches = urnComponent.path.match(URN_REG);
      if (matches && matches[0] === urnComponent.path) {
        let scheme = options.scheme || urnComponent.scheme || "urn";
        urnComponent.nid = matches[1].toLowerCase(), urnComponent.nss = matches[2];
        let urnScheme = `${scheme}:${options.nid || urnComponent.nid}`, schemeHandler = getSchemeHandler(urnScheme);
        urnComponent.path = void 0, schemeHandler && (urnComponent = schemeHandler.parse(urnComponent, options));
      } else
        urnComponent.error = urnComponent.error || "URN can not be parsed.";
      return urnComponent;
    }
    function urnSerialize(urnComponent, options) {
      if (urnComponent.nid === void 0)
        throw new Error("URN without nid cannot be serialized");
      let scheme = options.scheme || urnComponent.scheme || "urn", nid = urnComponent.nid.toLowerCase(), urnScheme = `${scheme}:${options.nid || nid}`, schemeHandler = getSchemeHandler(urnScheme);
      schemeHandler && (urnComponent = schemeHandler.serialize(urnComponent, options));
      let uriComponent = urnComponent, nss = urnComponent.nss;
      return uriComponent.path = `${nid || options.nid}:${nss}`, options.skipEscape = !0, uriComponent;
    }
    function urnuuidParse(urnComponent, options) {
      let uuidComponent = urnComponent;
      return uuidComponent.uuid = uuidComponent.nss, uuidComponent.nss = void 0, !options.tolerant && (!uuidComponent.uuid || !isUUID(uuidComponent.uuid)) && (uuidComponent.error = uuidComponent.error || "UUID is not valid."), uuidComponent;
    }
    function urnuuidSerialize(uuidComponent) {
      let urnComponent = uuidComponent;
      return urnComponent.nss = (uuidComponent.uuid || "").toLowerCase(), urnComponent;
    }
    var http2 = (
      /** @type {SchemeHandler} */
      {
        scheme: "http",
        domainHost: !0,
        parse: httpParse,
        serialize: httpSerialize
      }
    ), https = (
      /** @type {SchemeHandler} */
      {
        scheme: "https",
        domainHost: http2.domainHost,
        parse: httpParse,
        serialize: httpSerialize
      }
    ), ws = (
      /** @type {SchemeHandler} */
      {
        scheme: "ws",
        domainHost: !0,
        parse: wsParse,
        serialize: wsSerialize
      }
    ), wss = (
      /** @type {SchemeHandler} */
      {
        scheme: "wss",
        domainHost: ws.domainHost,
        parse: ws.parse,
        serialize: ws.serialize
      }
    ), urn = (
      /** @type {SchemeHandler} */
      {
        scheme: "urn",
        parse: urnParse,
        serialize: urnSerialize,
        skipNormalize: !0
      }
    ), urnuuid = (
      /** @type {SchemeHandler} */
      {
        scheme: "urn:uuid",
        parse: urnuuidParse,
        serialize: urnuuidSerialize,
        skipNormalize: !0
      }
    ), SCHEMES = (
      /** @type {Record<SchemeName, SchemeHandler>} */
      {
        http: http2,
        https,
        ws,
        wss,
        urn,
        "urn:uuid": urnuuid
      }
    );
    Object.setPrototypeOf(SCHEMES, null);
    function getSchemeHandler(scheme) {
      return scheme && (SCHEMES[
        /** @type {SchemeName} */
        scheme
      ] || SCHEMES[
        /** @type {SchemeName} */
        scheme.toLowerCase()
      ]) || void 0;
    }
    module.exports = {
      wsIsSecure,
      SCHEMES,
      isValidSchemeName,
      getSchemeHandler
    };
  }
});

// node_modules/fast-uri/index.js
var require_fast_uri = __commonJS({
  "node_modules/fast-uri/index.js"(exports, module) {
    "use strict";
    var { normalizeIPv6, removeDotSegments, removeDotSegmentsRelative, recomposeAuthority, normalizePercentEncoding, normalizePathEncoding, serializePathEncoding, normalizeQueryFragmentEncoding, encodeQuery, encodeFragment, reescapeHostDelimiters, isIPv4, nonSimpleDomain } = require_utils(), { SCHEMES, getSchemeHandler } = require_schemes(), VALID_SCHEME = /^[A-Za-z][A-Za-z0-9+.-]*$/u, MALFORMED_SCHEME_ERROR = "URI scheme is malformed.", MALFORMED_HOST_ERROR = "URI host is malformed.", FORBIDDEN_DOMAIN_HOST_CHARACTER = /[\0-\x20"#/:<>?@[\\\]^`{|}\x7F]/u;
    function validateScheme(scheme) {
      let stringScheme = String(scheme);
      if (!VALID_SCHEME.test(stringScheme))
        throw new TypeError(MALFORMED_SCHEME_ERROR);
      return stringScheme;
    }
    function normalize(uri, options) {
      return typeof uri == "string" ? uri = /** @type {T} */
      normalizeString(uri, options) : typeof uri == "object" && (uri = /** @type {T} */
      parse3(serialize(uri, options), options)), uri;
    }
    function resolve(baseURI, relativeURI, options) {
      let schemelessOptions = options ? Object.assign({ scheme: "null" }, options) : { scheme: "null" }, {
        parsed: baseParsed,
        malformedAuthorityOrPort: baseMalformed,
        malformedPercentEncoding: baseMalformedPercentEncoding,
        malformedSchemeSpecific: baseMalformedSchemeSpecific,
        malformedHost: baseMalformedHost,
        malformedScheme: baseMalformedScheme
      } = parseWithStatus(baseURI, schemelessOptions), {
        parsed: relativeParsed,
        malformedAuthorityOrPort: relativeMalformed,
        malformedPercentEncoding: relativeMalformedPercentEncoding,
        malformedSchemeSpecific: relativeMalformedSchemeSpecific,
        malformedHost: relativeMalformedHost,
        malformedScheme: relativeMalformedScheme
      } = parseWithStatus(relativeURI, schemelessOptions);
      if (baseMalformed || relativeMalformed || baseMalformedPercentEncoding || relativeMalformedPercentEncoding || baseMalformedSchemeSpecific || relativeMalformedSchemeSpecific || baseMalformedHost || relativeMalformedHost || baseMalformedScheme || relativeMalformedScheme)
        throw new Error(baseParsed.error || relativeParsed.error || "URI is malformed.");
      let resolved = resolveComponent(baseParsed, relativeParsed, schemelessOptions, !0), resolvedSchemeHandler = getSchemeHandler(options && options.scheme || resolved.scheme), resolvedHost = resolved.host, resolvedHostIsIP = resolvedHost !== void 0 && resolvedHost !== "" && (isIPv4(resolvedHost) || normalizeIPv6(resolvedHost).isIPV6);
      if (hasForbiddenDomainHost(resolved, options || {}, resolvedSchemeHandler, resolvedHostIsIP))
        throw new Error(MALFORMED_HOST_ERROR);
      canonicalizeHost(resolved, options || {}, resolvedSchemeHandler, resolvedHostIsIP);
      let encodedASCIIHost = resolvedHost && resolvedHost.indexOf("%") !== -1 && !new RegExp("\\P{ASCII}", "u").test(resolvedHost);
      if (resolved.error && !encodedASCIIHost)
        throw new Error(resolved.error);
      return schemelessOptions.skipEscape = !0, serialize(resolved, schemelessOptions);
    }
    function resolveComponent(base, relative, options, skipNormalization) {
      let target = {};
      if (skipNormalization || (base = parse3(serialize(base, options), options), relative = parse3(serialize(relative, options), options)), options = options || {}, !options.tolerant && relative.scheme)
        target.scheme = relative.scheme, target.userinfo = relative.userinfo, target.host = relative.host, target.port = relative.port, target.path = removeDotSegments(relative.path || ""), target.query = relative.query;
      else {
        if (relative.userinfo !== void 0 || relative.host !== void 0 || relative.port !== void 0)
          target.userinfo = relative.userinfo, target.host = relative.host, target.port = relative.port, target.path = removeDotSegments(relative.path || ""), target.query = relative.query;
        else {
          if (!relative.path)
            target.path = base.path, relative.query !== void 0 ? target.query = relative.query : target.query = base.query;
          else {
            if (relative.path[0] === "/")
              target.path = removeDotSegments(relative.path);
            else {
              let baseHasAuthority = base.userinfo !== void 0 || base.host !== void 0 || base.port !== void 0;
              baseHasAuthority && !base.path ? target.path = "/" + relative.path : base.path ? target.path = base.path.slice(0, base.path.lastIndexOf("/") + 1) + relative.path : target.path = relative.path, target.path = base.scheme === void 0 && !baseHasAuthority && target.path[0] !== "/" ? removeDotSegmentsRelative(target.path) : removeDotSegments(target.path);
            }
            target.query = relative.query;
          }
          target.userinfo = base.userinfo, target.host = base.host, target.port = base.port;
        }
        target.scheme = base.scheme;
      }
      return target.fragment = relative.fragment, target;
    }
    function equal(uriA, uriB, options) {
      let normalizedA = normalizeComparableURI(uriA, options), normalizedB = normalizeComparableURI(uriB, options);
      return normalizedA !== void 0 && normalizedB !== void 0 && normalizedA === normalizedB;
    }
    function serialize(cmpts, opts) {
      let component = {
        host: cmpts.host,
        scheme: cmpts.scheme,
        userinfo: cmpts.userinfo,
        port: cmpts.port,
        path: cmpts.path,
        query: cmpts.query,
        nid: cmpts.nid,
        nss: cmpts.nss,
        uuid: cmpts.uuid,
        fragment: cmpts.fragment,
        reference: cmpts.reference,
        resourceName: cmpts.resourceName,
        secure: cmpts.secure,
        error: ""
      }, options = Object.assign({}, opts), uriTokens = [];
      component.scheme && (component.scheme = validateScheme(component.scheme));
      let schemeHandler = getSchemeHandler(options.scheme || component.scheme);
      schemeHandler && schemeHandler.serialize && schemeHandler.serialize(component, options);
      let hasAuthority = component.userinfo !== void 0 || component.host !== void 0 || component.port !== void 0, schemelessReference = (component.scheme === void 0 || options.reference === "suffix") && !hasAuthority, pathNoScheme = !options.skipEscape && schemelessReference;
      component.path !== void 0 && (options.skipEscape ? component.path = normalizePercentEncoding(component.path) : component.path = serializePathEncoding(component.path, pathNoScheme)), options.reference !== "suffix" && component.scheme && (component.scheme = validateScheme(component.scheme), uriTokens.push(component.scheme, ":"));
      let authority = recomposeAuthority(component);
      if (authority !== void 0 && (options.reference !== "suffix" && uriTokens.push("//"), uriTokens.push(authority), component.path && component.path[0] !== "/" && uriTokens.push("/")), component.path !== void 0) {
        let s = component.path;
        !options.absolutePath && (!schemeHandler || !schemeHandler.absolutePath) && (s = schemelessReference && s[0] !== "/" ? removeDotSegmentsRelative(s) : removeDotSegments(s)), schemelessReference && (s = serializePathEncoding(s, !0)), authority === void 0 && s[0] === "/" && s[1] === "/" && (s = "/%2F" + s.slice(2)), uriTokens.push(s);
      }
      return component.query !== void 0 && uriTokens.push("?", encodeQuery(component.query)), component.fragment !== void 0 && uriTokens.push("#", encodeFragment(component.fragment)), uriTokens.join("");
    }
    var URI_PARSE = /^(?:([^#/:?]+):)?(?:\/\/((?:([^#/?@]*)@)?(\[[^#/?\]]+\]|[^#/:?]*)(?::(\d*))?))?([^#?]*)(?:\?([^#]*))?(?:#((?:.|[\n\r])*))?/u, AUTHORITY_PREFIX = /^(?:[^#/:?]+:)?\/\/([^/?#]*)/, AUTHORITY_INTRODUCER_REGION = /^(?:[^#/:?]+:)?([/\\\t\n\r]*)/;
    function getParseError(parsed, matches) {
      if (matches[2] !== void 0 && parsed.path && parsed.path[0] !== "/")
        return 'URI path must start with "/" when authority is present.';
      if (typeof parsed.port == "number" && (parsed.port < 0 || parsed.port > 65535))
        return "URI port is malformed.";
    }
    function hasMalformedPercentEncoding(component) {
      if (component === void 0) return !1;
      let percent = component.indexOf("%");
      for (; percent !== -1; ) {
        if (percent + 2 >= component.length || !/^[\da-f]{2}$/iu.test(component.slice(percent + 1, percent + 3)))
          return !0;
        percent = component.indexOf("%", percent + 3);
      }
      return !1;
    }
    function isIPLiteral(host) {
      return host[0] === "[" && host[host.length - 1] === "]";
    }
    function hasMalformedComponentPercentEncoding(matches) {
      let host = matches[4];
      return hasMalformedPercentEncoding(matches[3]) || host !== void 0 && !isIPLiteral(host) && hasMalformedPercentEncoding(host) || hasMalformedPercentEncoding(matches[6]) || hasMalformedPercentEncoding(matches[7]) || hasMalformedPercentEncoding(matches[8]);
    }
    function canonicalizeHost(parsed, options, schemeHandler, isIP) {
      if (hasForbiddenDomainHost(parsed, options, schemeHandler, isIP))
        return parsed.error = parsed.error || MALFORMED_HOST_ERROR, !0;
      if (!options.unicodeSupport && (!schemeHandler || !schemeHandler.unicodeSupport) && parsed.host && !isIPLiteral(parsed.host) && (options.domainHost || schemeHandler && schemeHandler.domainHost) && isIP === !1 && nonSimpleDomain(parsed.host)) {
        let url;
        try {
          url = new URL("http://" + parsed.host);
        } catch (e) {
          return parsed.error = parsed.error || "Host's domain name can not be converted to ASCII: " + e, !0;
        }
        if (!isHostOnlyURL(url))
          return parsed.error = parsed.error || MALFORMED_HOST_ERROR, !0;
        parsed.host = url.hostname;
      }
      return !1;
    }
    function hasForbiddenDomainHost(parsed, options, schemeHandler, isIP) {
      return !!(parsed.host && isIP === !1 && !isIPLiteral(parsed.host) && (options.domainHost || schemeHandler && schemeHandler.domainHost) && FORBIDDEN_DOMAIN_HOST_CHARACTER.test(parsed.host));
    }
    function isHostOnlyURL(url) {
      return url.username === "" && url.password === "" && url.port === "" && url.pathname === "/" && url.search === "" && url.hash === "" && url.hostname !== "";
    }
    function parseWithStatus(uri, opts) {
      let options = Object.assign({}, opts), parsed = {
        scheme: void 0,
        userinfo: void 0,
        host: "",
        port: void 0,
        path: "",
        query: void 0,
        fragment: void 0
      }, malformedAuthorityOrPort = !1, malformedPercentEncoding = !1, malformedSchemeSpecific = !1, malformedHost = !1, malformedIPLiteral = !1, malformedScheme = !1, isIP = !1;
      options.reference === "suffix" && (options.scheme ? uri = options.scheme + ":" + uri : uri = "//" + uri);
      let authorityMatch = uri.match(AUTHORITY_PREFIX);
      authorityMatch !== null && authorityMatch[1].indexOf("\\") !== -1 && (parsed.error = "URI authority must not contain a literal backslash.", malformedAuthorityOrPort = !0);
      let introducerMatch = uri.match(AUTHORITY_INTRODUCER_REGION);
      if (introducerMatch !== null) {
        let region = introducerMatch[1], normalizedRegion = region.replace(/[\t\n\r]/g, "");
        normalizedRegion.length >= 2 && (normalizedRegion.slice(0, 2) !== "//" ? (parsed.error = parsed.error || "URI authority must not contain a literal backslash.", malformedAuthorityOrPort = !0) : region.length !== normalizedRegion.length && (parsed.error = parsed.error || "URI authority introducer must not contain whitespace.", malformedAuthorityOrPort = !0));
      }
      let matches = uri.match(URI_PARSE);
      if (matches) {
        parsed.scheme = matches[1], parsed.userinfo = matches[3], parsed.host = matches[4], parsed.port = parseInt(matches[5], 10), parsed.path = matches[6] || "", parsed.query = matches[7], parsed.fragment = matches[8], parsed.scheme !== void 0 && (VALID_SCHEME.test(parsed.scheme) ? parsed.scheme = parsed.scheme.toLowerCase() : (parsed.error = parsed.error || MALFORMED_SCHEME_ERROR, malformedScheme = !0)), malformedPercentEncoding = hasMalformedComponentPercentEncoding(matches), malformedPercentEncoding && (parsed.error = parsed.error || "URI contains malformed percent-encoding."), isNaN(parsed.port) && (parsed.port = matches[5]);
        let parseError = getParseError(parsed, matches);
        if (parseError !== void 0 && (parsed.error = parsed.error || parseError, malformedAuthorityOrPort = !0), parsed.host)
          if (isIPv4(parsed.host) === !1) {
            let bracketedIPLiteral = isIPLiteral(parsed.host), hasIPLiteralBracket = parsed.host.indexOf("[") !== -1 || parsed.host.indexOf("]") !== -1, ipv6result = normalizeIPv6(parsed.host);
            isIP = ipv6result.isIPV6 || ipv6result.isIPVFuture === !0, malformedIPLiteral = hasIPLiteralBracket && (!bracketedIPLiteral || ipv6result.error === !0), parsed.host = isIP ? ipv6result.host : ipv6result.host.toLowerCase(), malformedIPLiteral && (parsed.error = parsed.error || MALFORMED_HOST_ERROR, malformedAuthorityOrPort = !0);
          } else
            isIP = !0;
        parsed.scheme === void 0 && parsed.userinfo === void 0 && parsed.host === void 0 && parsed.port === void 0 && parsed.query === void 0 && !parsed.path ? parsed.reference = "same-document" : parsed.scheme === void 0 ? parsed.reference = "relative" : parsed.fragment === void 0 ? parsed.reference = "absolute" : parsed.reference = "uri", options.reference && options.reference !== "suffix" && options.reference !== parsed.reference && (parsed.error = parsed.error || "URI is not a " + options.reference + " reference.");
        let schemeHandler = getSchemeHandler(options.scheme || parsed.scheme);
        if (malformedIPLiteral || (malformedHost = canonicalizeHost(parsed, options, schemeHandler, isIP)), uri.indexOf("%") !== -1 && parsed.host !== void 0 && !malformedIPLiteral) {
          let host = isIP ? parsed.host : normalizePercentEncoding(parsed.host, !0);
          isIP || (host = normalizePercentEncoding(host.toLowerCase())), parsed.host = isIP || options.scheme !== "null" ? reescapeHostDelimiters(host, isIP) : host;
        }
        (!schemeHandler || schemeHandler && !schemeHandler.skipNormalize) && (parsed.path && (parsed.path = normalizePathEncoding(parsed.path)), parsed.query && (parsed.query = normalizeQueryFragmentEncoding(parsed.query)), parsed.fragment && (parsed.fragment = normalizeQueryFragmentEncoding(parsed.fragment))), schemeHandler && schemeHandler.parse && (schemeHandler.parse(parsed, options), schemeHandler === SCHEMES.urn && parsed.nid === void 0 && (malformedSchemeSpecific = !0));
      } else
        parsed.error = parsed.error || "URI can not be parsed.";
      return { parsed, malformedAuthorityOrPort, malformedPercentEncoding, malformedSchemeSpecific, malformedHost, malformedScheme };
    }
    function parse3(uri, opts) {
      return parseWithStatus(uri, opts).parsed;
    }
    function normalizeString(uri, opts) {
      return normalizeStringWithStatus(uri, opts).normalized;
    }
    function normalizeStringWithStatus(uri, opts) {
      let { parsed, malformedAuthorityOrPort, malformedPercentEncoding, malformedSchemeSpecific, malformedHost, malformedScheme } = parseWithStatus(uri, opts);
      return {
        normalized: malformedAuthorityOrPort || malformedPercentEncoding || malformedSchemeSpecific || malformedHost || malformedScheme ? uri : serialize(parsed, opts),
        malformedAuthorityOrPort,
        malformedPercentEncoding,
        malformedSchemeSpecific,
        malformedHost,
        malformedScheme
      };
    }
    function normalizeComparableURI(uri, opts) {
      if (typeof uri != "string" && typeof uri != "object")
        return;
      let value;
      try {
        value = typeof uri == "string" ? uri : serialize(uri, opts);
      } catch {
        return;
      }
      let { normalized, malformedAuthorityOrPort, malformedPercentEncoding, malformedSchemeSpecific, malformedHost, malformedScheme } = normalizeStringWithStatus(value, opts);
      return malformedAuthorityOrPort || malformedPercentEncoding || malformedSchemeSpecific || malformedHost || malformedScheme ? void 0 : normalized;
    }
    var fastUri = {
      SCHEMES,
      normalize,
      resolve,
      resolveComponent,
      equal,
      serialize,
      parse: parse3
    };
    module.exports = fastUri;
    module.exports.default = fastUri;
    module.exports.fastUri = fastUri;
  }
});

// node_modules/ajv/dist/runtime/uri.js
var require_uri = __commonJS({
  "node_modules/ajv/dist/runtime/uri.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var uri = require_fast_uri();
    uri.code = 'require("ajv/dist/runtime/uri").default';
    exports.default = uri;
  }
});

// node_modules/ajv/dist/core.js
var require_core = __commonJS({
  "node_modules/ajv/dist/core.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.CodeGen = exports.Name = exports.nil = exports.stringify = exports.str = exports._ = exports.KeywordCxt = void 0;
    var validate_1 = require_validate();
    Object.defineProperty(exports, "KeywordCxt", { enumerable: !0, get: function() {
      return validate_1.KeywordCxt;
    } });
    var codegen_1 = require_codegen();
    Object.defineProperty(exports, "_", { enumerable: !0, get: function() {
      return codegen_1._;
    } });
    Object.defineProperty(exports, "str", { enumerable: !0, get: function() {
      return codegen_1.str;
    } });
    Object.defineProperty(exports, "stringify", { enumerable: !0, get: function() {
      return codegen_1.stringify;
    } });
    Object.defineProperty(exports, "nil", { enumerable: !0, get: function() {
      return codegen_1.nil;
    } });
    Object.defineProperty(exports, "Name", { enumerable: !0, get: function() {
      return codegen_1.Name;
    } });
    Object.defineProperty(exports, "CodeGen", { enumerable: !0, get: function() {
      return codegen_1.CodeGen;
    } });
    var validation_error_1 = require_validation_error(), ref_error_1 = require_ref_error(), rules_1 = require_rules(), compile_1 = require_compile(), codegen_2 = require_codegen(), resolve_1 = require_resolve(), dataType_1 = require_dataType(), util_1 = require_util(), $dataRefSchema = require_data(), uri_1 = require_uri(), defaultRegExp = (str, flags) => new RegExp(str, flags);
    defaultRegExp.code = "new RegExp";
    var META_IGNORE_OPTIONS = ["removeAdditional", "useDefaults", "coerceTypes"], EXT_SCOPE_NAMES = /* @__PURE__ */ new Set([
      "validate",
      "serialize",
      "parse",
      "wrapper",
      "root",
      "schema",
      "keyword",
      "pattern",
      "formats",
      "validate$data",
      "func",
      "obj",
      "Error"
    ]), removedOptions = {
      errorDataPath: "",
      format: "`validateFormats: false` can be used instead.",
      nullable: '"nullable" keyword is supported by default.',
      jsonPointers: "Deprecated jsPropertySyntax can be used instead.",
      extendRefs: "Deprecated ignoreKeywordsWithRef can be used instead.",
      missingRefs: "Pass empty schema with $id that should be ignored to ajv.addSchema.",
      processCode: "Use option `code: {process: (code, schemaEnv: object) => string}`",
      sourceCode: "Use option `code: {source: true}`",
      strictDefaults: "It is default now, see option `strict`.",
      strictKeywords: "It is default now, see option `strict`.",
      uniqueItems: '"uniqueItems" keyword is always validated.',
      unknownFormats: "Disable strict mode or pass `true` to `ajv.addFormat` (or `formats` option).",
      cache: "Map is used as cache, schema object as key.",
      serialize: "Map is used as cache, schema object as key.",
      ajvErrors: "It is default now."
    }, deprecatedOptions = {
      ignoreKeywordsWithRef: "",
      jsPropertySyntax: "",
      unicode: '"minLength"/"maxLength" account for unicode characters by default.'
    }, MAX_EXPRESSION = 200;
    function requiredOptions(o) {
      var _a, _b, _c, _d, _e, _f, _g, _h, _j, _k, _l, _m, _o, _p, _q, _r, _s, _t, _u, _v, _w, _x, _y, _z, _0;
      let s = o.strict, _optz = (_a = o.code) === null || _a === void 0 ? void 0 : _a.optimize, optimize = _optz === !0 || _optz === void 0 ? 1 : _optz || 0, regExp = (_c = (_b = o.code) === null || _b === void 0 ? void 0 : _b.regExp) !== null && _c !== void 0 ? _c : defaultRegExp, uriResolver = (_d = o.uriResolver) !== null && _d !== void 0 ? _d : uri_1.default;
      return {
        strictSchema: (_f = (_e = o.strictSchema) !== null && _e !== void 0 ? _e : s) !== null && _f !== void 0 ? _f : !0,
        strictNumbers: (_h = (_g = o.strictNumbers) !== null && _g !== void 0 ? _g : s) !== null && _h !== void 0 ? _h : !0,
        strictTypes: (_k = (_j = o.strictTypes) !== null && _j !== void 0 ? _j : s) !== null && _k !== void 0 ? _k : "log",
        strictTuples: (_m = (_l = o.strictTuples) !== null && _l !== void 0 ? _l : s) !== null && _m !== void 0 ? _m : "log",
        strictRequired: (_p = (_o = o.strictRequired) !== null && _o !== void 0 ? _o : s) !== null && _p !== void 0 ? _p : !1,
        code: o.code ? { ...o.code, optimize, regExp } : { optimize, regExp },
        loopRequired: (_q = o.loopRequired) !== null && _q !== void 0 ? _q : MAX_EXPRESSION,
        loopEnum: (_r = o.loopEnum) !== null && _r !== void 0 ? _r : MAX_EXPRESSION,
        meta: (_s = o.meta) !== null && _s !== void 0 ? _s : !0,
        messages: (_t = o.messages) !== null && _t !== void 0 ? _t : !0,
        inlineRefs: (_u = o.inlineRefs) !== null && _u !== void 0 ? _u : !0,
        schemaId: (_v = o.schemaId) !== null && _v !== void 0 ? _v : "$id",
        addUsedSchema: (_w = o.addUsedSchema) !== null && _w !== void 0 ? _w : !0,
        validateSchema: (_x = o.validateSchema) !== null && _x !== void 0 ? _x : !0,
        validateFormats: (_y = o.validateFormats) !== null && _y !== void 0 ? _y : !0,
        unicodeRegExp: (_z = o.unicodeRegExp) !== null && _z !== void 0 ? _z : !0,
        int32range: (_0 = o.int32range) !== null && _0 !== void 0 ? _0 : !0,
        uriResolver
      };
    }
    var Ajv2 = class {
      constructor(opts = {}) {
        this.schemas = {}, this.refs = {}, this.formats = /* @__PURE__ */ Object.create(null), this._compilations = /* @__PURE__ */ new Set(), this._loading = {}, this._cache = /* @__PURE__ */ new Map(), opts = this.opts = { ...opts, ...requiredOptions(opts) };
        let { es5, lines } = this.opts.code;
        this.scope = new codegen_2.ValueScope({ scope: {}, prefixes: EXT_SCOPE_NAMES, es5, lines }), this.logger = getLogger(opts.logger);
        let formatOpt = opts.validateFormats;
        opts.validateFormats = !1, this.RULES = (0, rules_1.getRules)(), checkOptions.call(this, removedOptions, opts, "NOT SUPPORTED"), checkOptions.call(this, deprecatedOptions, opts, "DEPRECATED", "warn"), this._metaOpts = getMetaSchemaOptions.call(this), opts.formats && addInitialFormats.call(this), this._addVocabularies(), this._addDefaultMetaSchema(), opts.keywords && addInitialKeywords.call(this, opts.keywords), typeof opts.meta == "object" && this.addMetaSchema(opts.meta), addInitialSchemas.call(this), opts.validateFormats = formatOpt;
      }
      _addVocabularies() {
        this.addKeyword("$async");
      }
      _addDefaultMetaSchema() {
        let { $data, meta, schemaId } = this.opts, _dataRefSchema = $dataRefSchema;
        schemaId === "id" && (_dataRefSchema = { ...$dataRefSchema }, _dataRefSchema.id = _dataRefSchema.$id, delete _dataRefSchema.$id), meta && $data && this.addMetaSchema(_dataRefSchema, _dataRefSchema[schemaId], !1);
      }
      defaultMeta() {
        let { meta, schemaId } = this.opts;
        return this.opts.defaultMeta = typeof meta == "object" ? meta[schemaId] || meta : void 0;
      }
      validate(schemaKeyRef, data) {
        let v;
        if (typeof schemaKeyRef == "string") {
          if (v = this.getSchema(schemaKeyRef), !v)
            throw new Error(`no schema with key or ref "${schemaKeyRef}"`);
        } else
          v = this.compile(schemaKeyRef);
        let valid = v(data);
        return "$async" in v || (this.errors = v.errors), valid;
      }
      compile(schema, _meta) {
        let sch = this._addSchema(schema, _meta);
        return sch.validate || this._compileSchemaEnv(sch);
      }
      compileAsync(schema, meta) {
        if (typeof this.opts.loadSchema != "function")
          throw new Error("options.loadSchema should be a function");
        let { loadSchema } = this.opts;
        return runCompileAsync.call(this, schema, meta);
        async function runCompileAsync(_schema, _meta) {
          await loadMetaSchema.call(this, _schema.$schema);
          let sch = this._addSchema(_schema, _meta);
          return sch.validate || _compileAsync.call(this, sch);
        }
        async function loadMetaSchema($ref) {
          $ref && !this.getSchema($ref) && await runCompileAsync.call(this, { $ref }, !0);
        }
        async function _compileAsync(sch) {
          try {
            return this._compileSchemaEnv(sch);
          } catch (e) {
            if (!(e instanceof ref_error_1.default))
              throw e;
            return checkLoaded.call(this, e), await loadMissingSchema.call(this, e.missingSchema), _compileAsync.call(this, sch);
          }
        }
        function checkLoaded({ missingSchema: ref2, missingRef }) {
          if (this.refs[ref2])
            throw new Error(`AnySchema ${ref2} is loaded but ${missingRef} cannot be resolved`);
        }
        async function loadMissingSchema(ref2) {
          let _schema = await _loadSchema.call(this, ref2);
          this.refs[ref2] || await loadMetaSchema.call(this, _schema.$schema), this.refs[ref2] || this.addSchema(_schema, ref2, meta);
        }
        async function _loadSchema(ref2) {
          let p = this._loading[ref2];
          if (p)
            return p;
          try {
            return await (this._loading[ref2] = loadSchema(ref2));
          } finally {
            delete this._loading[ref2];
          }
        }
      }
      // Adds schema to the instance
      addSchema(schema, key, _meta, _validateSchema = this.opts.validateSchema) {
        if (Array.isArray(schema)) {
          for (let sch of schema)
            this.addSchema(sch, void 0, _meta, _validateSchema);
          return this;
        }
        let id;
        if (typeof schema == "object") {
          let { schemaId } = this.opts;
          if (id = schema[schemaId], id !== void 0 && typeof id != "string")
            throw new Error(`schema ${schemaId} must be string`);
        }
        return key = (0, resolve_1.normalizeId)(key || id), this._checkUnique(key), this.schemas[key] = this._addSchema(schema, _meta, key, _validateSchema, !0), this;
      }
      // Add schema that will be used to validate other schemas
      // options in META_IGNORE_OPTIONS are alway set to false
      addMetaSchema(schema, key, _validateSchema = this.opts.validateSchema) {
        return this.addSchema(schema, key, !0, _validateSchema), this;
      }
      //  Validate schema against its meta-schema
      validateSchema(schema, throwOrLogError) {
        if (typeof schema == "boolean")
          return !0;
        let $schema;
        if ($schema = schema.$schema, $schema !== void 0 && typeof $schema != "string")
          throw new Error("$schema must be a string");
        if ($schema = $schema || this.opts.defaultMeta || this.defaultMeta(), !$schema)
          return this.logger.warn("meta-schema not available"), this.errors = null, !0;
        let valid = this.validate($schema, schema);
        if (!valid && throwOrLogError) {
          let message = "schema is invalid: " + this.errorsText();
          if (this.opts.validateSchema === "log")
            this.logger.error(message);
          else
            throw new Error(message);
        }
        return valid;
      }
      // Get compiled schema by `key` or `ref`.
      // (`key` that was passed to `addSchema` or full schema reference - `schema.$id` or resolved id)
      getSchema(keyRef) {
        let sch;
        for (; typeof (sch = getSchEnv.call(this, keyRef)) == "string"; )
          keyRef = sch;
        if (sch === void 0) {
          let { schemaId } = this.opts, root = new compile_1.SchemaEnv({ schema: {}, schemaId });
          if (sch = compile_1.resolveSchema.call(this, root, keyRef), !sch)
            return;
          this.refs[keyRef] = sch;
        }
        return sch.validate || this._compileSchemaEnv(sch);
      }
      // Remove cached schema(s).
      // If no parameter is passed all schemas but meta-schemas are removed.
      // If RegExp is passed all schemas with key/id matching pattern but meta-schemas are removed.
      // Even if schema is referenced by other schemas it still can be removed as other schemas have local references.
      removeSchema(schemaKeyRef) {
        if (schemaKeyRef instanceof RegExp)
          return this._removeAllSchemas(this.schemas, schemaKeyRef), this._removeAllSchemas(this.refs, schemaKeyRef), this;
        switch (typeof schemaKeyRef) {
          case "undefined":
            return this._removeAllSchemas(this.schemas), this._removeAllSchemas(this.refs), this._cache.clear(), this;
          case "string": {
            let sch = getSchEnv.call(this, schemaKeyRef);
            return typeof sch == "object" && this._cache.delete(sch.schema), delete this.schemas[schemaKeyRef], delete this.refs[schemaKeyRef], this;
          }
          case "object": {
            let cacheKey2 = schemaKeyRef;
            this._cache.delete(cacheKey2);
            let id = schemaKeyRef[this.opts.schemaId];
            return id && (id = (0, resolve_1.normalizeId)(id), delete this.schemas[id], delete this.refs[id]), this;
          }
          default:
            throw new Error("ajv.removeSchema: invalid parameter");
        }
      }
      // add "vocabulary" - a collection of keywords
      addVocabulary(definitions) {
        for (let def of definitions)
          this.addKeyword(def);
        return this;
      }
      addKeyword(kwdOrDef, def) {
        let keyword;
        if (typeof kwdOrDef == "string")
          keyword = kwdOrDef, typeof def == "object" && (this.logger.warn("these parameters are deprecated, see docs for addKeyword"), def.keyword = keyword);
        else if (typeof kwdOrDef == "object" && def === void 0) {
          if (def = kwdOrDef, keyword = def.keyword, Array.isArray(keyword) && !keyword.length)
            throw new Error("addKeywords: keyword must be string or non-empty array");
        } else
          throw new Error("invalid addKeywords parameters");
        if (checkKeyword.call(this, keyword, def), !def)
          return (0, util_1.eachItem)(keyword, (kwd) => addRule.call(this, kwd)), this;
        keywordMetaschema.call(this, def);
        let definition = {
          ...def,
          type: (0, dataType_1.getJSONTypes)(def.type),
          schemaType: (0, dataType_1.getJSONTypes)(def.schemaType)
        };
        return (0, util_1.eachItem)(keyword, definition.type.length === 0 ? (k) => addRule.call(this, k, definition) : (k) => definition.type.forEach((t) => addRule.call(this, k, definition, t))), this;
      }
      getKeyword(keyword) {
        let rule = this.RULES.all[keyword];
        return typeof rule == "object" ? rule.definition : !!rule;
      }
      // Remove keyword
      removeKeyword(keyword) {
        let { RULES } = this;
        delete RULES.keywords[keyword], delete RULES.all[keyword];
        for (let group of RULES.rules) {
          let i = group.rules.findIndex((rule) => rule.keyword === keyword);
          i >= 0 && group.rules.splice(i, 1);
        }
        return this;
      }
      // Add format
      addFormat(name, format) {
        return typeof format == "string" && (format = new RegExp(format)), this.formats[name] = format, this;
      }
      errorsText(errors = this.errors, { separator = ", ", dataVar = "data" } = {}) {
        return !errors || errors.length === 0 ? "No errors" : errors.map((e) => `${dataVar}${e.instancePath} ${e.message}`).reduce((text, msg) => text + separator + msg);
      }
      $dataMetaSchema(metaSchema, keywordsJsonPointers) {
        let rules = this.RULES.all;
        metaSchema = JSON.parse(JSON.stringify(metaSchema));
        for (let jsonPointer of keywordsJsonPointers) {
          let segments = jsonPointer.split("/").slice(1), keywords = metaSchema;
          for (let seg of segments)
            keywords = keywords[seg];
          for (let key in rules) {
            let rule = rules[key];
            if (typeof rule != "object")
              continue;
            let { $data } = rule.definition, schema = keywords[key];
            $data && schema && (keywords[key] = schemaOrData(schema));
          }
        }
        return metaSchema;
      }
      _removeAllSchemas(schemas, regex) {
        for (let keyRef in schemas) {
          let sch = schemas[keyRef];
          (!regex || regex.test(keyRef)) && (typeof sch == "string" ? delete schemas[keyRef] : sch && !sch.meta && (this._cache.delete(sch.schema), delete schemas[keyRef]));
        }
      }
      _addSchema(schema, meta, baseId, validateSchema = this.opts.validateSchema, addSchema = this.opts.addUsedSchema) {
        let id, { schemaId } = this.opts;
        if (typeof schema == "object")
          id = schema[schemaId];
        else {
          if (this.opts.jtd)
            throw new Error("schema must be object");
          if (typeof schema != "boolean")
            throw new Error("schema must be object or boolean");
        }
        let sch = this._cache.get(schema);
        if (sch !== void 0)
          return sch;
        baseId = (0, resolve_1.normalizeId)(id || baseId);
        let localRefs = resolve_1.getSchemaRefs.call(this, schema, baseId);
        return sch = new compile_1.SchemaEnv({ schema, schemaId, meta, baseId, localRefs }), this._cache.set(sch.schema, sch), addSchema && !baseId.startsWith("#") && (baseId && this._checkUnique(baseId), this.refs[baseId] = sch), validateSchema && this.validateSchema(schema, !0), sch;
      }
      _checkUnique(id) {
        if (this.schemas[id] || this.refs[id])
          throw new Error(`schema with key or id "${id}" already exists`);
      }
      _compileSchemaEnv(sch) {
        if (sch.meta ? this._compileMetaSchema(sch) : compile_1.compileSchema.call(this, sch), !sch.validate)
          throw new Error("ajv implementation error");
        return sch.validate;
      }
      _compileMetaSchema(sch) {
        let currentOpts = this.opts;
        this.opts = this._metaOpts;
        try {
          compile_1.compileSchema.call(this, sch);
        } finally {
          this.opts = currentOpts;
        }
      }
    };
    Ajv2.ValidationError = validation_error_1.default;
    Ajv2.MissingRefError = ref_error_1.default;
    exports.default = Ajv2;
    function checkOptions(checkOpts, options, msg, log2 = "error") {
      for (let key in checkOpts) {
        let opt = key;
        opt in options && this.logger[log2](`${msg}: option ${key}. ${checkOpts[opt]}`);
      }
    }
    function getSchEnv(keyRef) {
      return keyRef = (0, resolve_1.normalizeId)(keyRef), this.schemas[keyRef] || this.refs[keyRef];
    }
    function addInitialSchemas() {
      let optsSchemas = this.opts.schemas;
      if (optsSchemas)
        if (Array.isArray(optsSchemas))
          this.addSchema(optsSchemas);
        else
          for (let key in optsSchemas)
            this.addSchema(optsSchemas[key], key);
    }
    function addInitialFormats() {
      for (let name in this.opts.formats) {
        let format = this.opts.formats[name];
        format && this.addFormat(name, format);
      }
    }
    function addInitialKeywords(defs) {
      if (Array.isArray(defs)) {
        this.addVocabulary(defs);
        return;
      }
      this.logger.warn("keywords option as map is deprecated, pass array");
      for (let keyword in defs) {
        let def = defs[keyword];
        def.keyword || (def.keyword = keyword), this.addKeyword(def);
      }
    }
    function getMetaSchemaOptions() {
      let metaOpts = { ...this.opts };
      for (let opt of META_IGNORE_OPTIONS)
        delete metaOpts[opt];
      return metaOpts;
    }
    var noLogs = { log() {
    }, warn() {
    }, error() {
    } };
    function getLogger(logger) {
      if (logger === !1)
        return noLogs;
      if (logger === void 0)
        return console;
      if (logger.log && logger.warn && logger.error)
        return logger;
      throw new Error("logger must implement log, warn and error methods");
    }
    var KEYWORD_NAME = /^[a-z_$][a-z0-9_$:-]*$/i;
    function checkKeyword(keyword, def) {
      let { RULES } = this;
      if ((0, util_1.eachItem)(keyword, (kwd) => {
        if (RULES.keywords[kwd])
          throw new Error(`Keyword ${kwd} is already defined`);
        if (!KEYWORD_NAME.test(kwd))
          throw new Error(`Keyword ${kwd} has invalid name`);
      }), !!def && def.$data && !("code" in def || "validate" in def))
        throw new Error('$data keyword must have "code" or "validate" function');
    }
    function addRule(keyword, definition, dataType) {
      var _a;
      let post = definition?.post;
      if (dataType && post)
        throw new Error('keyword with "post" flag cannot have "type"');
      let { RULES } = this, ruleGroup = post ? RULES.post : RULES.rules.find(({ type: t }) => t === dataType);
      if (ruleGroup || (ruleGroup = { type: dataType, rules: [] }, RULES.rules.push(ruleGroup)), RULES.keywords[keyword] = !0, !definition)
        return;
      let rule = {
        keyword,
        definition: {
          ...definition,
          type: (0, dataType_1.getJSONTypes)(definition.type),
          schemaType: (0, dataType_1.getJSONTypes)(definition.schemaType)
        }
      };
      definition.before ? addBeforeRule.call(this, ruleGroup, rule, definition.before) : ruleGroup.rules.push(rule), RULES.all[keyword] = rule, (_a = definition.implements) === null || _a === void 0 || _a.forEach((kwd) => this.addKeyword(kwd));
    }
    function addBeforeRule(ruleGroup, rule, before) {
      let i = ruleGroup.rules.findIndex((_rule) => _rule.keyword === before);
      i >= 0 ? ruleGroup.rules.splice(i, 0, rule) : (ruleGroup.rules.push(rule), this.logger.warn(`rule ${before} is not defined`));
    }
    function keywordMetaschema(def) {
      let { metaSchema } = def;
      metaSchema !== void 0 && (def.$data && this.opts.$data && (metaSchema = schemaOrData(metaSchema)), def.validateSchema = this.compile(metaSchema, !0));
    }
    var $dataRef = {
      $ref: "https://raw.githubusercontent.com/ajv-validator/ajv/master/lib/refs/data.json#"
    };
    function schemaOrData(schema) {
      return { anyOf: [schema, $dataRef] };
    }
  }
});

// node_modules/ajv/dist/vocabularies/core/id.js
var require_id = __commonJS({
  "node_modules/ajv/dist/vocabularies/core/id.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var def = {
      keyword: "id",
      code() {
        throw new Error('NOT SUPPORTED: keyword "id", use "$id" for schema ID');
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/core/ref.js
var require_ref = __commonJS({
  "node_modules/ajv/dist/vocabularies/core/ref.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.callRef = exports.getValidate = void 0;
    var ref_error_1 = require_ref_error(), code_1 = require_code2(), codegen_1 = require_codegen(), names_1 = require_names(), compile_1 = require_compile(), util_1 = require_util(), def = {
      keyword: "$ref",
      schemaType: "string",
      code(cxt) {
        let { gen, schema: $ref, it } = cxt, { baseId, schemaEnv: env2, validateName, opts, self } = it, { root } = env2;
        if (($ref === "#" || $ref === "#/") && baseId === root.baseId)
          return callRootRef();
        let schOrEnv = compile_1.resolveRef.call(self, root, baseId, $ref);
        if (schOrEnv === void 0)
          throw new ref_error_1.default(it.opts.uriResolver, baseId, $ref);
        if (schOrEnv instanceof compile_1.SchemaEnv)
          return callValidate(schOrEnv);
        return inlineRefSchema(schOrEnv);
        function callRootRef() {
          if (env2 === root)
            return callRef(cxt, validateName, env2, env2.$async);
          let rootName = gen.scopeValue("root", { ref: root });
          return callRef(cxt, (0, codegen_1._)`${rootName}.validate`, root, root.$async);
        }
        function callValidate(sch) {
          let v = getValidate(cxt, sch);
          callRef(cxt, v, sch, sch.$async);
        }
        function inlineRefSchema(sch) {
          let schName = gen.scopeValue("schema", opts.code.source === !0 ? { ref: sch, code: (0, codegen_1.stringify)(sch) } : { ref: sch }), valid = gen.name("valid"), schCxt = cxt.subschema({
            schema: sch,
            dataTypes: [],
            schemaPath: codegen_1.nil,
            topSchemaRef: schName,
            errSchemaPath: $ref
          }, valid);
          cxt.mergeEvaluated(schCxt), cxt.ok(valid);
        }
      }
    };
    function getValidate(cxt, sch) {
      let { gen } = cxt;
      return sch.validate ? gen.scopeValue("validate", { ref: sch.validate }) : (0, codegen_1._)`${gen.scopeValue("wrapper", { ref: sch })}.validate`;
    }
    exports.getValidate = getValidate;
    function callRef(cxt, v, sch, $async) {
      let { gen, it } = cxt, { allErrors, schemaEnv: env2, opts } = it, passCxt = opts.passContext ? names_1.default.this : codegen_1.nil;
      $async ? callAsyncRef() : callSyncRef();
      function callAsyncRef() {
        if (!env2.$async)
          throw new Error("async schema referenced by sync schema");
        let valid = gen.let("valid");
        gen.try(() => {
          gen.code((0, codegen_1._)`await ${(0, code_1.callValidateCode)(cxt, v, passCxt)}`), addEvaluatedFrom(v), allErrors || gen.assign(valid, !0);
        }, (e) => {
          gen.if((0, codegen_1._)`!(${e} instanceof ${it.ValidationError})`, () => gen.throw(e)), addErrorsFrom(e), allErrors || gen.assign(valid, !1);
        }), cxt.ok(valid);
      }
      function callSyncRef() {
        cxt.result((0, code_1.callValidateCode)(cxt, v, passCxt), () => addEvaluatedFrom(v), () => addErrorsFrom(v));
      }
      function addErrorsFrom(source) {
        let errs = (0, codegen_1._)`${source}.errors`;
        gen.assign(names_1.default.vErrors, (0, codegen_1._)`${names_1.default.vErrors} === null ? ${errs} : ${names_1.default.vErrors}.concat(${errs})`), gen.assign(names_1.default.errors, (0, codegen_1._)`${names_1.default.vErrors}.length`);
      }
      function addEvaluatedFrom(source) {
        var _a;
        if (!it.opts.unevaluated)
          return;
        let schEvaluated = (_a = sch?.validate) === null || _a === void 0 ? void 0 : _a.evaluated;
        if (it.props !== !0)
          if (schEvaluated && !schEvaluated.dynamicProps)
            schEvaluated.props !== void 0 && (it.props = util_1.mergeEvaluated.props(gen, schEvaluated.props, it.props));
          else {
            let props = gen.var("props", (0, codegen_1._)`${source}.evaluated.props`);
            it.props = util_1.mergeEvaluated.props(gen, props, it.props, codegen_1.Name);
          }
        if (it.items !== !0)
          if (schEvaluated && !schEvaluated.dynamicItems)
            schEvaluated.items !== void 0 && (it.items = util_1.mergeEvaluated.items(gen, schEvaluated.items, it.items));
          else {
            let items = gen.var("items", (0, codegen_1._)`${source}.evaluated.items`);
            it.items = util_1.mergeEvaluated.items(gen, items, it.items, codegen_1.Name);
          }
      }
    }
    exports.callRef = callRef;
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/core/index.js
var require_core2 = __commonJS({
  "node_modules/ajv/dist/vocabularies/core/index.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var id_1 = require_id(), ref_1 = require_ref(), core = [
      "$schema",
      "$id",
      "$defs",
      "$vocabulary",
      { keyword: "$comment" },
      "definitions",
      id_1.default,
      ref_1.default
    ];
    exports.default = core;
  }
});

// node_modules/ajv/dist/vocabularies/validation/limitNumber.js
var require_limitNumber = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/limitNumber.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), ops = codegen_1.operators, KWDs = {
      maximum: { okStr: "<=", ok: ops.LTE, fail: ops.GT },
      minimum: { okStr: ">=", ok: ops.GTE, fail: ops.LT },
      exclusiveMaximum: { okStr: "<", ok: ops.LT, fail: ops.GTE },
      exclusiveMinimum: { okStr: ">", ok: ops.GT, fail: ops.LTE }
    }, error2 = {
      message: ({ keyword, schemaCode }) => (0, codegen_1.str)`must be ${KWDs[keyword].okStr} ${schemaCode}`,
      params: ({ keyword, schemaCode }) => (0, codegen_1._)`{comparison: ${KWDs[keyword].okStr}, limit: ${schemaCode}}`
    }, def = {
      keyword: Object.keys(KWDs),
      type: "number",
      schemaType: "number",
      $data: !0,
      error: error2,
      code(cxt) {
        let { keyword, data, schemaCode } = cxt;
        cxt.fail$data((0, codegen_1._)`${data} ${KWDs[keyword].fail} ${schemaCode} || isNaN(${data})`);
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/validation/multipleOf.js
var require_multipleOf = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/multipleOf.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), error2 = {
      message: ({ schemaCode }) => (0, codegen_1.str)`must be multiple of ${schemaCode}`,
      params: ({ schemaCode }) => (0, codegen_1._)`{multipleOf: ${schemaCode}}`
    }, def = {
      keyword: "multipleOf",
      type: "number",
      schemaType: "number",
      $data: !0,
      error: error2,
      code(cxt) {
        let { gen, data, schemaCode, it } = cxt, prec = it.opts.multipleOfPrecision, res = gen.let("res"), invalid = prec ? (0, codegen_1._)`Math.abs(Math.round(${res}) - ${res}) > 1e-${prec}` : (0, codegen_1._)`${res} !== parseInt(${res})`;
        cxt.fail$data((0, codegen_1._)`(${schemaCode} === 0 || (${res} = ${data}/${schemaCode}, ${invalid}))`);
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/runtime/ucs2length.js
var require_ucs2length = __commonJS({
  "node_modules/ajv/dist/runtime/ucs2length.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    function ucs2length(str) {
      let len = str.length, length = 0, pos = 0, value;
      for (; pos < len; )
        length++, value = str.charCodeAt(pos++), value >= 55296 && value <= 56319 && pos < len && (value = str.charCodeAt(pos), (value & 64512) === 56320 && pos++);
      return length;
    }
    exports.default = ucs2length;
    ucs2length.code = 'require("ajv/dist/runtime/ucs2length").default';
  }
});

// node_modules/ajv/dist/vocabularies/validation/limitLength.js
var require_limitLength = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/limitLength.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), util_1 = require_util(), ucs2length_1 = require_ucs2length(), error2 = {
      message({ keyword, schemaCode }) {
        let comp = keyword === "maxLength" ? "more" : "fewer";
        return (0, codegen_1.str)`must NOT have ${comp} than ${schemaCode} characters`;
      },
      params: ({ schemaCode }) => (0, codegen_1._)`{limit: ${schemaCode}}`
    }, def = {
      keyword: ["maxLength", "minLength"],
      type: "string",
      schemaType: "number",
      $data: !0,
      error: error2,
      code(cxt) {
        let { keyword, data, schemaCode, it } = cxt, op = keyword === "maxLength" ? codegen_1.operators.GT : codegen_1.operators.LT, len = it.opts.unicode === !1 ? (0, codegen_1._)`${data}.length` : (0, codegen_1._)`${(0, util_1.useFunc)(cxt.gen, ucs2length_1.default)}(${data})`;
        cxt.fail$data((0, codegen_1._)`${len} ${op} ${schemaCode}`);
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/validation/pattern.js
var require_pattern = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/pattern.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var code_1 = require_code2(), util_1 = require_util(), codegen_1 = require_codegen(), error2 = {
      message: ({ schemaCode }) => (0, codegen_1.str)`must match pattern "${schemaCode}"`,
      params: ({ schemaCode }) => (0, codegen_1._)`{pattern: ${schemaCode}}`
    }, def = {
      keyword: "pattern",
      type: "string",
      schemaType: "string",
      $data: !0,
      error: error2,
      code(cxt) {
        let { gen, data, $data, schema, schemaCode, it } = cxt, u = it.opts.unicodeRegExp ? "u" : "";
        if ($data) {
          let { regExp } = it.opts.code, regExpCode = regExp.code === "new RegExp" ? (0, codegen_1._)`new RegExp` : (0, util_1.useFunc)(gen, regExp), valid = gen.let("valid");
          gen.try(() => gen.assign(valid, (0, codegen_1._)`${regExpCode}(${schemaCode}, ${u}).test(${data})`), () => gen.assign(valid, !1)), cxt.fail$data((0, codegen_1._)`!${valid}`);
        } else {
          let regExp = (0, code_1.usePattern)(cxt, schema);
          cxt.fail$data((0, codegen_1._)`!${regExp}.test(${data})`);
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/validation/limitProperties.js
var require_limitProperties = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/limitProperties.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), error2 = {
      message({ keyword, schemaCode }) {
        let comp = keyword === "maxProperties" ? "more" : "fewer";
        return (0, codegen_1.str)`must NOT have ${comp} than ${schemaCode} properties`;
      },
      params: ({ schemaCode }) => (0, codegen_1._)`{limit: ${schemaCode}}`
    }, def = {
      keyword: ["maxProperties", "minProperties"],
      type: "object",
      schemaType: "number",
      $data: !0,
      error: error2,
      code(cxt) {
        let { keyword, data, schemaCode } = cxt, op = keyword === "maxProperties" ? codegen_1.operators.GT : codegen_1.operators.LT;
        cxt.fail$data((0, codegen_1._)`Object.keys(${data}).length ${op} ${schemaCode}`);
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/validation/required.js
var require_required = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/required.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var code_1 = require_code2(), codegen_1 = require_codegen(), util_1 = require_util(), error2 = {
      message: ({ params: { missingProperty } }) => (0, codegen_1.str)`must have required property '${missingProperty}'`,
      params: ({ params: { missingProperty } }) => (0, codegen_1._)`{missingProperty: ${missingProperty}}`
    }, def = {
      keyword: "required",
      type: "object",
      schemaType: "array",
      $data: !0,
      error: error2,
      code(cxt) {
        let { gen, schema, schemaCode, data, $data, it } = cxt, { opts } = it;
        if (!$data && schema.length === 0)
          return;
        let useLoop = schema.length >= opts.loopRequired;
        if (it.allErrors ? allErrorsMode() : exitOnErrorMode(), opts.strictRequired) {
          let props = cxt.parentSchema.properties, { definedProperties } = cxt.it;
          for (let requiredKey of schema)
            if (props?.[requiredKey] === void 0 && !definedProperties.has(requiredKey)) {
              let schemaPath = it.schemaEnv.baseId + it.errSchemaPath, msg = `required property "${requiredKey}" is not defined at "${schemaPath}" (strictRequired)`;
              (0, util_1.checkStrictMode)(it, msg, it.opts.strictRequired);
            }
        }
        function allErrorsMode() {
          if (useLoop || $data)
            cxt.block$data(codegen_1.nil, loopAllRequired);
          else
            for (let prop of schema)
              (0, code_1.checkReportMissingProp)(cxt, prop);
        }
        function exitOnErrorMode() {
          let missing = gen.let("missing");
          if (useLoop || $data) {
            let valid = gen.let("valid", !0);
            cxt.block$data(valid, () => loopUntilMissing(missing, valid)), cxt.ok(valid);
          } else
            gen.if((0, code_1.checkMissingProp)(cxt, schema, missing)), (0, code_1.reportMissingProp)(cxt, missing), gen.else();
        }
        function loopAllRequired() {
          gen.forOf("prop", schemaCode, (prop) => {
            cxt.setParams({ missingProperty: prop }), gen.if((0, code_1.noPropertyInData)(gen, data, prop, opts.ownProperties), () => cxt.error());
          });
        }
        function loopUntilMissing(missing, valid) {
          cxt.setParams({ missingProperty: missing }), gen.forOf(missing, schemaCode, () => {
            gen.assign(valid, (0, code_1.propertyInData)(gen, data, missing, opts.ownProperties)), gen.if((0, codegen_1.not)(valid), () => {
              cxt.error(), gen.break();
            });
          }, codegen_1.nil);
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/validation/limitItems.js
var require_limitItems = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/limitItems.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), error2 = {
      message({ keyword, schemaCode }) {
        let comp = keyword === "maxItems" ? "more" : "fewer";
        return (0, codegen_1.str)`must NOT have ${comp} than ${schemaCode} items`;
      },
      params: ({ schemaCode }) => (0, codegen_1._)`{limit: ${schemaCode}}`
    }, def = {
      keyword: ["maxItems", "minItems"],
      type: "array",
      schemaType: "number",
      $data: !0,
      error: error2,
      code(cxt) {
        let { keyword, data, schemaCode } = cxt, op = keyword === "maxItems" ? codegen_1.operators.GT : codegen_1.operators.LT;
        cxt.fail$data((0, codegen_1._)`${data}.length ${op} ${schemaCode}`);
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/runtime/equal.js
var require_equal = __commonJS({
  "node_modules/ajv/dist/runtime/equal.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var equal = require_fast_deep_equal();
    equal.code = 'require("ajv/dist/runtime/equal").default';
    exports.default = equal;
  }
});

// node_modules/ajv/dist/vocabularies/validation/uniqueItems.js
var require_uniqueItems = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/uniqueItems.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var dataType_1 = require_dataType(), codegen_1 = require_codegen(), util_1 = require_util(), equal_1 = require_equal(), error2 = {
      message: ({ params: { i, j } }) => (0, codegen_1.str)`must NOT have duplicate items (items ## ${j} and ${i} are identical)`,
      params: ({ params: { i, j } }) => (0, codegen_1._)`{i: ${i}, j: ${j}}`
    }, def = {
      keyword: "uniqueItems",
      type: "array",
      schemaType: "boolean",
      $data: !0,
      error: error2,
      code(cxt) {
        let { gen, data, $data, schema, parentSchema, schemaCode, it } = cxt;
        if (!$data && !schema)
          return;
        let valid = gen.let("valid"), itemTypes = parentSchema.items ? (0, dataType_1.getSchemaTypes)(parentSchema.items) : [];
        cxt.block$data(valid, validateUniqueItems, (0, codegen_1._)`${schemaCode} === false`), cxt.ok(valid);
        function validateUniqueItems() {
          let i = gen.let("i", (0, codegen_1._)`${data}.length`), j = gen.let("j");
          cxt.setParams({ i, j }), gen.assign(valid, !0), gen.if((0, codegen_1._)`${i} > 1`, () => (canOptimize() ? loopN : loopN2)(i, j));
        }
        function canOptimize() {
          return itemTypes.length > 0 && !itemTypes.some((t) => t === "object" || t === "array");
        }
        function loopN(i, j) {
          let item = gen.name("item"), wrongType = (0, dataType_1.checkDataTypes)(itemTypes, item, it.opts.strictNumbers, dataType_1.DataType.Wrong), indices = gen.const("indices", (0, codegen_1._)`{}`);
          gen.for((0, codegen_1._)`;${i}--;`, () => {
            gen.let(item, (0, codegen_1._)`${data}[${i}]`), gen.if(wrongType, (0, codegen_1._)`continue`), itemTypes.length > 1 && gen.if((0, codegen_1._)`typeof ${item} == "string"`, (0, codegen_1._)`${item} += "_"`), gen.if((0, codegen_1._)`typeof ${indices}[${item}] == "number"`, () => {
              gen.assign(j, (0, codegen_1._)`${indices}[${item}]`), cxt.error(), gen.assign(valid, !1).break();
            }).code((0, codegen_1._)`${indices}[${item}] = ${i}`);
          });
        }
        function loopN2(i, j) {
          let eql = (0, util_1.useFunc)(gen, equal_1.default), outer = gen.name("outer");
          gen.label(outer).for((0, codegen_1._)`;${i}--;`, () => gen.for((0, codegen_1._)`${j} = ${i}; ${j}--;`, () => gen.if((0, codegen_1._)`${eql}(${data}[${i}], ${data}[${j}])`, () => {
            cxt.error(), gen.assign(valid, !1).break(outer);
          })));
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/validation/const.js
var require_const = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/const.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), util_1 = require_util(), equal_1 = require_equal(), error2 = {
      message: "must be equal to constant",
      params: ({ schemaCode }) => (0, codegen_1._)`{allowedValue: ${schemaCode}}`
    }, def = {
      keyword: "const",
      $data: !0,
      error: error2,
      code(cxt) {
        let { gen, data, $data, schemaCode, schema } = cxt;
        $data || schema && typeof schema == "object" ? cxt.fail$data((0, codegen_1._)`!${(0, util_1.useFunc)(gen, equal_1.default)}(${data}, ${schemaCode})`) : cxt.fail((0, codegen_1._)`${schema} !== ${data}`);
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/validation/enum.js
var require_enum = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/enum.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), util_1 = require_util(), equal_1 = require_equal(), error2 = {
      message: "must be equal to one of the allowed values",
      params: ({ schemaCode }) => (0, codegen_1._)`{allowedValues: ${schemaCode}}`
    }, def = {
      keyword: "enum",
      schemaType: "array",
      $data: !0,
      error: error2,
      code(cxt) {
        let { gen, data, $data, schema, schemaCode, it } = cxt;
        if (!$data && schema.length === 0)
          throw new Error("enum must have non-empty array");
        let useLoop = schema.length >= it.opts.loopEnum, eql, getEql = () => eql ?? (eql = (0, util_1.useFunc)(gen, equal_1.default)), valid;
        if (useLoop || $data)
          valid = gen.let("valid"), cxt.block$data(valid, loopEnum);
        else {
          if (!Array.isArray(schema))
            throw new Error("ajv implementation error");
          let vSchema = gen.const("vSchema", schemaCode);
          valid = (0, codegen_1.or)(...schema.map((_x, i) => equalCode(vSchema, i)));
        }
        cxt.pass(valid);
        function loopEnum() {
          gen.assign(valid, !1), gen.forOf("v", schemaCode, (v) => gen.if((0, codegen_1._)`${getEql()}(${data}, ${v})`, () => gen.assign(valid, !0).break()));
        }
        function equalCode(vSchema, i) {
          let sch = schema[i];
          return typeof sch == "object" && sch !== null ? (0, codegen_1._)`${getEql()}(${data}, ${vSchema}[${i}])` : (0, codegen_1._)`${data} === ${sch}`;
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/validation/index.js
var require_validation = __commonJS({
  "node_modules/ajv/dist/vocabularies/validation/index.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var limitNumber_1 = require_limitNumber(), multipleOf_1 = require_multipleOf(), limitLength_1 = require_limitLength(), pattern_1 = require_pattern(), limitProperties_1 = require_limitProperties(), required_1 = require_required(), limitItems_1 = require_limitItems(), uniqueItems_1 = require_uniqueItems(), const_1 = require_const(), enum_1 = require_enum(), validation = [
      // number
      limitNumber_1.default,
      multipleOf_1.default,
      // string
      limitLength_1.default,
      pattern_1.default,
      // object
      limitProperties_1.default,
      required_1.default,
      // array
      limitItems_1.default,
      uniqueItems_1.default,
      // any
      { keyword: "type", schemaType: ["string", "array"] },
      { keyword: "nullable", schemaType: "boolean" },
      const_1.default,
      enum_1.default
    ];
    exports.default = validation;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/additionalItems.js
var require_additionalItems = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/additionalItems.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.validateAdditionalItems = void 0;
    var codegen_1 = require_codegen(), util_1 = require_util(), error2 = {
      message: ({ params: { len } }) => (0, codegen_1.str)`must NOT have more than ${len} items`,
      params: ({ params: { len } }) => (0, codegen_1._)`{limit: ${len}}`
    }, def = {
      keyword: "additionalItems",
      type: "array",
      schemaType: ["boolean", "object"],
      before: "uniqueItems",
      error: error2,
      code(cxt) {
        let { parentSchema, it } = cxt, { items } = parentSchema;
        if (!Array.isArray(items)) {
          (0, util_1.checkStrictMode)(it, '"additionalItems" is ignored when "items" is not an array of schemas');
          return;
        }
        validateAdditionalItems(cxt, items);
      }
    };
    function validateAdditionalItems(cxt, items) {
      let { gen, schema, data, keyword, it } = cxt;
      it.items = !0;
      let len = gen.const("len", (0, codegen_1._)`${data}.length`);
      if (schema === !1)
        cxt.setParams({ len: items.length }), cxt.pass((0, codegen_1._)`${len} <= ${items.length}`);
      else if (typeof schema == "object" && !(0, util_1.alwaysValidSchema)(it, schema)) {
        let valid = gen.var("valid", (0, codegen_1._)`${len} <= ${items.length}`);
        gen.if((0, codegen_1.not)(valid), () => validateItems(valid)), cxt.ok(valid);
      }
      function validateItems(valid) {
        gen.forRange("i", items.length, len, (i) => {
          cxt.subschema({ keyword, dataProp: i, dataPropType: util_1.Type.Num }, valid), it.allErrors || gen.if((0, codegen_1.not)(valid), () => gen.break());
        });
      }
    }
    exports.validateAdditionalItems = validateAdditionalItems;
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/items.js
var require_items = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/items.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.validateTuple = void 0;
    var codegen_1 = require_codegen(), util_1 = require_util(), code_1 = require_code2(), def = {
      keyword: "items",
      type: "array",
      schemaType: ["object", "array", "boolean"],
      before: "uniqueItems",
      code(cxt) {
        let { schema, it } = cxt;
        if (Array.isArray(schema))
          return validateTuple(cxt, "additionalItems", schema);
        it.items = !0, !(0, util_1.alwaysValidSchema)(it, schema) && cxt.ok((0, code_1.validateArray)(cxt));
      }
    };
    function validateTuple(cxt, extraItems, schArr = cxt.schema) {
      let { gen, parentSchema, data, keyword, it } = cxt;
      checkStrictTuple(parentSchema), it.opts.unevaluated && schArr.length && it.items !== !0 && (it.items = util_1.mergeEvaluated.items(gen, schArr.length, it.items));
      let valid = gen.name("valid"), len = gen.const("len", (0, codegen_1._)`${data}.length`);
      schArr.forEach((sch, i) => {
        (0, util_1.alwaysValidSchema)(it, sch) || (gen.if((0, codegen_1._)`${len} > ${i}`, () => cxt.subschema({
          keyword,
          schemaProp: i,
          dataProp: i
        }, valid)), cxt.ok(valid));
      });
      function checkStrictTuple(sch) {
        let { opts, errSchemaPath } = it, l = schArr.length, fullTuple = l === sch.minItems && (l === sch.maxItems || sch[extraItems] === !1);
        if (opts.strictTuples && !fullTuple) {
          let msg = `"${keyword}" is ${l}-tuple, but minItems or maxItems/${extraItems} are not specified or different at path "${errSchemaPath}"`;
          (0, util_1.checkStrictMode)(it, msg, opts.strictTuples);
        }
      }
    }
    exports.validateTuple = validateTuple;
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/prefixItems.js
var require_prefixItems = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/prefixItems.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var items_1 = require_items(), def = {
      keyword: "prefixItems",
      type: "array",
      schemaType: ["array"],
      before: "uniqueItems",
      code: (cxt) => (0, items_1.validateTuple)(cxt, "items")
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/items2020.js
var require_items2020 = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/items2020.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), util_1 = require_util(), code_1 = require_code2(), additionalItems_1 = require_additionalItems(), error2 = {
      message: ({ params: { len } }) => (0, codegen_1.str)`must NOT have more than ${len} items`,
      params: ({ params: { len } }) => (0, codegen_1._)`{limit: ${len}}`
    }, def = {
      keyword: "items",
      type: "array",
      schemaType: ["object", "boolean"],
      before: "uniqueItems",
      error: error2,
      code(cxt) {
        let { schema, parentSchema, it } = cxt, { prefixItems } = parentSchema;
        it.items = !0, !(0, util_1.alwaysValidSchema)(it, schema) && (prefixItems ? (0, additionalItems_1.validateAdditionalItems)(cxt, prefixItems) : cxt.ok((0, code_1.validateArray)(cxt)));
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/contains.js
var require_contains = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/contains.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), util_1 = require_util(), error2 = {
      message: ({ params: { min, max } }) => max === void 0 ? (0, codegen_1.str)`must contain at least ${min} valid item(s)` : (0, codegen_1.str)`must contain at least ${min} and no more than ${max} valid item(s)`,
      params: ({ params: { min, max } }) => max === void 0 ? (0, codegen_1._)`{minContains: ${min}}` : (0, codegen_1._)`{minContains: ${min}, maxContains: ${max}}`
    }, def = {
      keyword: "contains",
      type: "array",
      schemaType: ["object", "boolean"],
      before: "uniqueItems",
      trackErrors: !0,
      error: error2,
      code(cxt) {
        let { gen, schema, parentSchema, data, it } = cxt, min, max, { minContains, maxContains } = parentSchema;
        it.opts.next ? (min = minContains === void 0 ? 1 : minContains, max = maxContains) : min = 1;
        let len = gen.const("len", (0, codegen_1._)`${data}.length`);
        if (cxt.setParams({ min, max }), max === void 0 && min === 0) {
          (0, util_1.checkStrictMode)(it, '"minContains" == 0 without "maxContains": "contains" keyword ignored');
          return;
        }
        if (max !== void 0 && min > max) {
          (0, util_1.checkStrictMode)(it, '"minContains" > "maxContains" is always invalid'), cxt.fail();
          return;
        }
        if ((0, util_1.alwaysValidSchema)(it, schema)) {
          let cond = (0, codegen_1._)`${len} >= ${min}`;
          max !== void 0 && (cond = (0, codegen_1._)`${cond} && ${len} <= ${max}`), cxt.pass(cond);
          return;
        }
        it.items = !0;
        let valid = gen.name("valid");
        max === void 0 && min === 1 ? validateItems(valid, () => gen.if(valid, () => gen.break())) : min === 0 ? (gen.let(valid, !0), max !== void 0 && gen.if((0, codegen_1._)`${data}.length > 0`, validateItemsWithCount)) : (gen.let(valid, !1), validateItemsWithCount()), cxt.result(valid, () => cxt.reset());
        function validateItemsWithCount() {
          let schValid = gen.name("_valid"), count = gen.let("count", 0);
          validateItems(schValid, () => gen.if(schValid, () => checkLimits(count)));
        }
        function validateItems(_valid, block) {
          gen.forRange("i", 0, len, (i) => {
            cxt.subschema({
              keyword: "contains",
              dataProp: i,
              dataPropType: util_1.Type.Num,
              compositeRule: !0
            }, _valid), block();
          });
        }
        function checkLimits(count) {
          gen.code((0, codegen_1._)`${count}++`), max === void 0 ? gen.if((0, codegen_1._)`${count} >= ${min}`, () => gen.assign(valid, !0).break()) : (gen.if((0, codegen_1._)`${count} > ${max}`, () => gen.assign(valid, !1).break()), min === 1 ? gen.assign(valid, !0) : gen.if((0, codegen_1._)`${count} >= ${min}`, () => gen.assign(valid, !0)));
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/dependencies.js
var require_dependencies = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/dependencies.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.validateSchemaDeps = exports.validatePropertyDeps = exports.error = void 0;
    var codegen_1 = require_codegen(), util_1 = require_util(), code_1 = require_code2();
    exports.error = {
      message: ({ params: { property, depsCount, deps } }) => {
        let property_ies = depsCount === 1 ? "property" : "properties";
        return (0, codegen_1.str)`must have ${property_ies} ${deps} when property ${property} is present`;
      },
      params: ({ params: { property, depsCount, deps, missingProperty } }) => (0, codegen_1._)`{property: ${property},
    missingProperty: ${missingProperty},
    depsCount: ${depsCount},
    deps: ${deps}}`
      // TODO change to reference
    };
    var def = {
      keyword: "dependencies",
      type: "object",
      schemaType: "object",
      error: exports.error,
      code(cxt) {
        let [propDeps, schDeps] = splitDependencies(cxt);
        validatePropertyDeps(cxt, propDeps), validateSchemaDeps(cxt, schDeps);
      }
    };
    function splitDependencies({ schema }) {
      let propertyDeps = {}, schemaDeps = {};
      for (let key in schema) {
        if (key === "__proto__")
          continue;
        let deps = Array.isArray(schema[key]) ? propertyDeps : schemaDeps;
        deps[key] = schema[key];
      }
      return [propertyDeps, schemaDeps];
    }
    function validatePropertyDeps(cxt, propertyDeps = cxt.schema) {
      let { gen, data, it } = cxt;
      if (Object.keys(propertyDeps).length === 0)
        return;
      let missing = gen.let("missing");
      for (let prop in propertyDeps) {
        let deps = propertyDeps[prop];
        if (deps.length === 0)
          continue;
        let hasProperty = (0, code_1.propertyInData)(gen, data, prop, it.opts.ownProperties);
        cxt.setParams({
          property: prop,
          depsCount: deps.length,
          deps: deps.join(", ")
        }), it.allErrors ? gen.if(hasProperty, () => {
          for (let depProp of deps)
            (0, code_1.checkReportMissingProp)(cxt, depProp);
        }) : (gen.if((0, codegen_1._)`${hasProperty} && (${(0, code_1.checkMissingProp)(cxt, deps, missing)})`), (0, code_1.reportMissingProp)(cxt, missing), gen.else());
      }
    }
    exports.validatePropertyDeps = validatePropertyDeps;
    function validateSchemaDeps(cxt, schemaDeps = cxt.schema) {
      let { gen, data, keyword, it } = cxt, valid = gen.name("valid");
      for (let prop in schemaDeps)
        (0, util_1.alwaysValidSchema)(it, schemaDeps[prop]) || (gen.if(
          (0, code_1.propertyInData)(gen, data, prop, it.opts.ownProperties),
          () => {
            let schCxt = cxt.subschema({ keyword, schemaProp: prop }, valid);
            cxt.mergeValidEvaluated(schCxt, valid);
          },
          () => gen.var(valid, !0)
          // TODO var
        ), cxt.ok(valid));
    }
    exports.validateSchemaDeps = validateSchemaDeps;
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/propertyNames.js
var require_propertyNames = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/propertyNames.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), util_1 = require_util(), error2 = {
      message: "property name must be valid",
      params: ({ params }) => (0, codegen_1._)`{propertyName: ${params.propertyName}}`
    }, def = {
      keyword: "propertyNames",
      type: "object",
      schemaType: ["object", "boolean"],
      error: error2,
      code(cxt) {
        let { gen, schema, data, it } = cxt;
        if ((0, util_1.alwaysValidSchema)(it, schema))
          return;
        let valid = gen.name("valid");
        gen.forIn("key", data, (key) => {
          cxt.setParams({ propertyName: key }), cxt.subschema({
            keyword: "propertyNames",
            data: key,
            dataTypes: ["string"],
            propertyName: key,
            compositeRule: !0
          }, valid), gen.if((0, codegen_1.not)(valid), () => {
            cxt.error(!0), it.allErrors || gen.break();
          });
        }), cxt.ok(valid);
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/additionalProperties.js
var require_additionalProperties = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/additionalProperties.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var code_1 = require_code2(), codegen_1 = require_codegen(), names_1 = require_names(), util_1 = require_util(), error2 = {
      message: "must NOT have additional properties",
      params: ({ params }) => (0, codegen_1._)`{additionalProperty: ${params.additionalProperty}}`
    }, def = {
      keyword: "additionalProperties",
      type: ["object"],
      schemaType: ["boolean", "object"],
      allowUndefined: !0,
      trackErrors: !0,
      error: error2,
      code(cxt) {
        let { gen, schema, parentSchema, data, errsCount, it } = cxt;
        if (!errsCount)
          throw new Error("ajv implementation error");
        let { allErrors, opts } = it;
        if (it.props = !0, opts.removeAdditional !== "all" && (0, util_1.alwaysValidSchema)(it, schema))
          return;
        let props = (0, code_1.allSchemaProperties)(parentSchema.properties), patProps = (0, code_1.allSchemaProperties)(parentSchema.patternProperties);
        checkAdditionalProperties(), cxt.ok((0, codegen_1._)`${errsCount} === ${names_1.default.errors}`);
        function checkAdditionalProperties() {
          gen.forIn("key", data, (key) => {
            !props.length && !patProps.length ? additionalPropertyCode(key) : gen.if(isAdditional(key), () => additionalPropertyCode(key));
          });
        }
        function isAdditional(key) {
          let definedProp;
          if (props.length > 8) {
            let propsSchema = (0, util_1.schemaRefOrVal)(it, parentSchema.properties, "properties");
            definedProp = (0, code_1.isOwnProperty)(gen, propsSchema, key);
          } else props.length ? definedProp = (0, codegen_1.or)(...props.map((p) => (0, codegen_1._)`${key} === ${p}`)) : definedProp = codegen_1.nil;
          return patProps.length && (definedProp = (0, codegen_1.or)(definedProp, ...patProps.map((p) => (0, codegen_1._)`${(0, code_1.usePattern)(cxt, p)}.test(${key})`))), (0, codegen_1.not)(definedProp);
        }
        function deleteAdditional(key) {
          gen.code((0, codegen_1._)`delete ${data}[${key}]`);
        }
        function additionalPropertyCode(key) {
          if (opts.removeAdditional === "all" || opts.removeAdditional && schema === !1) {
            deleteAdditional(key);
            return;
          }
          if (schema === !1) {
            cxt.setParams({ additionalProperty: key }), cxt.error(), allErrors || gen.break();
            return;
          }
          if (typeof schema == "object" && !(0, util_1.alwaysValidSchema)(it, schema)) {
            let valid = gen.name("valid");
            opts.removeAdditional === "failing" ? (applyAdditionalSchema(key, valid, !1), gen.if((0, codegen_1.not)(valid), () => {
              cxt.reset(), deleteAdditional(key);
            })) : (applyAdditionalSchema(key, valid), allErrors || gen.if((0, codegen_1.not)(valid), () => gen.break()));
          }
        }
        function applyAdditionalSchema(key, valid, errors) {
          let subschema = {
            keyword: "additionalProperties",
            dataProp: key,
            dataPropType: util_1.Type.Str
          };
          errors === !1 && Object.assign(subschema, {
            compositeRule: !0,
            createErrors: !1,
            allErrors: !1
          }), cxt.subschema(subschema, valid);
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/properties.js
var require_properties = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/properties.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var validate_1 = require_validate(), code_1 = require_code2(), util_1 = require_util(), additionalProperties_1 = require_additionalProperties(), def = {
      keyword: "properties",
      type: "object",
      schemaType: "object",
      code(cxt) {
        let { gen, schema, parentSchema, data, it } = cxt;
        it.opts.removeAdditional === "all" && parentSchema.additionalProperties === void 0 && additionalProperties_1.default.code(new validate_1.KeywordCxt(it, additionalProperties_1.default, "additionalProperties"));
        let allProps = (0, code_1.allSchemaProperties)(schema);
        for (let prop of allProps)
          it.definedProperties.add(prop);
        it.opts.unevaluated && allProps.length && it.props !== !0 && (it.props = util_1.mergeEvaluated.props(gen, (0, util_1.toHash)(allProps), it.props));
        let properties = allProps.filter((p) => !(0, util_1.alwaysValidSchema)(it, schema[p]));
        if (properties.length === 0)
          return;
        let valid = gen.name("valid");
        for (let prop of properties)
          hasDefault(prop) ? applyPropertySchema(prop) : (gen.if((0, code_1.propertyInData)(gen, data, prop, it.opts.ownProperties)), applyPropertySchema(prop), it.allErrors || gen.else().var(valid, !0), gen.endIf()), cxt.it.definedProperties.add(prop), cxt.ok(valid);
        function hasDefault(prop) {
          return it.opts.useDefaults && !it.compositeRule && schema[prop].default !== void 0;
        }
        function applyPropertySchema(prop) {
          cxt.subschema({
            keyword: "properties",
            schemaProp: prop,
            dataProp: prop
          }, valid);
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/patternProperties.js
var require_patternProperties = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/patternProperties.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var code_1 = require_code2(), codegen_1 = require_codegen(), util_1 = require_util(), util_2 = require_util(), def = {
      keyword: "patternProperties",
      type: "object",
      schemaType: "object",
      code(cxt) {
        let { gen, schema, data, parentSchema, it } = cxt, { opts } = it, patterns = (0, code_1.allSchemaProperties)(schema), alwaysValidPatterns = patterns.filter((p) => (0, util_1.alwaysValidSchema)(it, schema[p]));
        if (patterns.length === 0 || alwaysValidPatterns.length === patterns.length && (!it.opts.unevaluated || it.props === !0))
          return;
        let checkProperties = opts.strictSchema && !opts.allowMatchingProperties && parentSchema.properties, valid = gen.name("valid");
        it.props !== !0 && !(it.props instanceof codegen_1.Name) && (it.props = (0, util_2.evaluatedPropsToName)(gen, it.props));
        let { props } = it;
        validatePatternProperties();
        function validatePatternProperties() {
          for (let pat of patterns)
            checkProperties && checkMatchingProperties(pat), it.allErrors ? validateProperties(pat) : (gen.var(valid, !0), validateProperties(pat), gen.if(valid));
        }
        function checkMatchingProperties(pat) {
          for (let prop in checkProperties)
            new RegExp(pat).test(prop) && (0, util_1.checkStrictMode)(it, `property ${prop} matches pattern ${pat} (use allowMatchingProperties)`);
        }
        function validateProperties(pat) {
          gen.forIn("key", data, (key) => {
            gen.if((0, codegen_1._)`${(0, code_1.usePattern)(cxt, pat)}.test(${key})`, () => {
              let alwaysValid = alwaysValidPatterns.includes(pat);
              alwaysValid || cxt.subschema({
                keyword: "patternProperties",
                schemaProp: pat,
                dataProp: key,
                dataPropType: util_2.Type.Str
              }, valid), it.opts.unevaluated && props !== !0 ? gen.assign((0, codegen_1._)`${props}[${key}]`, !0) : !alwaysValid && !it.allErrors && gen.if((0, codegen_1.not)(valid), () => gen.break());
            });
          });
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/not.js
var require_not = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/not.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var util_1 = require_util(), def = {
      keyword: "not",
      schemaType: ["object", "boolean"],
      trackErrors: !0,
      code(cxt) {
        let { gen, schema, it } = cxt;
        if ((0, util_1.alwaysValidSchema)(it, schema)) {
          cxt.fail();
          return;
        }
        let valid = gen.name("valid");
        cxt.subschema({
          keyword: "not",
          compositeRule: !0,
          createErrors: !1,
          allErrors: !1
        }, valid), cxt.failResult(valid, () => cxt.reset(), () => cxt.error());
      },
      error: { message: "must NOT be valid" }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/anyOf.js
var require_anyOf = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/anyOf.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var code_1 = require_code2(), def = {
      keyword: "anyOf",
      schemaType: "array",
      trackErrors: !0,
      code: code_1.validateUnion,
      error: { message: "must match a schema in anyOf" }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/oneOf.js
var require_oneOf = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/oneOf.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), util_1 = require_util(), error2 = {
      message: "must match exactly one schema in oneOf",
      params: ({ params }) => (0, codegen_1._)`{passingSchemas: ${params.passing}}`
    }, def = {
      keyword: "oneOf",
      schemaType: "array",
      trackErrors: !0,
      error: error2,
      code(cxt) {
        let { gen, schema, parentSchema, it } = cxt;
        if (!Array.isArray(schema))
          throw new Error("ajv implementation error");
        if (it.opts.discriminator && parentSchema.discriminator)
          return;
        let schArr = schema, valid = gen.let("valid", !1), passing = gen.let("passing", null), schValid = gen.name("_valid");
        cxt.setParams({ passing }), gen.block(validateOneOf), cxt.result(valid, () => cxt.reset(), () => cxt.error(!0));
        function validateOneOf() {
          schArr.forEach((sch, i) => {
            let schCxt;
            (0, util_1.alwaysValidSchema)(it, sch) ? gen.var(schValid, !0) : schCxt = cxt.subschema({
              keyword: "oneOf",
              schemaProp: i,
              compositeRule: !0
            }, schValid), i > 0 && gen.if((0, codegen_1._)`${schValid} && ${valid}`).assign(valid, !1).assign(passing, (0, codegen_1._)`[${passing}, ${i}]`).else(), gen.if(schValid, () => {
              gen.assign(valid, !0), gen.assign(passing, i), schCxt && cxt.mergeEvaluated(schCxt, codegen_1.Name);
            });
          });
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/allOf.js
var require_allOf = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/allOf.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var util_1 = require_util(), def = {
      keyword: "allOf",
      schemaType: "array",
      code(cxt) {
        let { gen, schema, it } = cxt;
        if (!Array.isArray(schema))
          throw new Error("ajv implementation error");
        let valid = gen.name("valid");
        schema.forEach((sch, i) => {
          if ((0, util_1.alwaysValidSchema)(it, sch))
            return;
          let schCxt = cxt.subschema({ keyword: "allOf", schemaProp: i }, valid);
          cxt.ok(valid), cxt.mergeEvaluated(schCxt);
        });
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/if.js
var require_if = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/if.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), util_1 = require_util(), error2 = {
      message: ({ params }) => (0, codegen_1.str)`must match "${params.ifClause}" schema`,
      params: ({ params }) => (0, codegen_1._)`{failingKeyword: ${params.ifClause}}`
    }, def = {
      keyword: "if",
      schemaType: ["object", "boolean"],
      trackErrors: !0,
      error: error2,
      code(cxt) {
        let { gen, parentSchema, it } = cxt;
        parentSchema.then === void 0 && parentSchema.else === void 0 && (0, util_1.checkStrictMode)(it, '"if" without "then" and "else" is ignored');
        let hasThen = hasSchema(it, "then"), hasElse = hasSchema(it, "else");
        if (!hasThen && !hasElse)
          return;
        let valid = gen.let("valid", !0), schValid = gen.name("_valid");
        if (validateIf(), cxt.reset(), hasThen && hasElse) {
          let ifClause = gen.let("ifClause");
          cxt.setParams({ ifClause }), gen.if(schValid, validateClause("then", ifClause), validateClause("else", ifClause));
        } else hasThen ? gen.if(schValid, validateClause("then")) : gen.if((0, codegen_1.not)(schValid), validateClause("else"));
        cxt.pass(valid, () => cxt.error(!0));
        function validateIf() {
          let schCxt = cxt.subschema({
            keyword: "if",
            compositeRule: !0,
            createErrors: !1,
            allErrors: !1
          }, schValid);
          cxt.mergeEvaluated(schCxt);
        }
        function validateClause(keyword, ifClause) {
          return () => {
            let schCxt = cxt.subschema({ keyword }, schValid);
            gen.assign(valid, schValid), cxt.mergeValidEvaluated(schCxt, valid), ifClause ? gen.assign(ifClause, (0, codegen_1._)`${keyword}`) : cxt.setParams({ ifClause: keyword });
          };
        }
      }
    };
    function hasSchema(it, keyword) {
      let schema = it.schema[keyword];
      return schema !== void 0 && !(0, util_1.alwaysValidSchema)(it, schema);
    }
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/thenElse.js
var require_thenElse = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/thenElse.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var util_1 = require_util(), def = {
      keyword: ["then", "else"],
      schemaType: ["object", "boolean"],
      code({ keyword, parentSchema, it }) {
        parentSchema.if === void 0 && (0, util_1.checkStrictMode)(it, `"${keyword}" without "if" is ignored`);
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/applicator/index.js
var require_applicator = __commonJS({
  "node_modules/ajv/dist/vocabularies/applicator/index.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var additionalItems_1 = require_additionalItems(), prefixItems_1 = require_prefixItems(), items_1 = require_items(), items2020_1 = require_items2020(), contains_1 = require_contains(), dependencies_1 = require_dependencies(), propertyNames_1 = require_propertyNames(), additionalProperties_1 = require_additionalProperties(), properties_1 = require_properties(), patternProperties_1 = require_patternProperties(), not_1 = require_not(), anyOf_1 = require_anyOf(), oneOf_1 = require_oneOf(), allOf_1 = require_allOf(), if_1 = require_if(), thenElse_1 = require_thenElse();
    function getApplicator(draft2020 = !1) {
      let applicator = [
        // any
        not_1.default,
        anyOf_1.default,
        oneOf_1.default,
        allOf_1.default,
        if_1.default,
        thenElse_1.default,
        // object
        propertyNames_1.default,
        additionalProperties_1.default,
        dependencies_1.default,
        properties_1.default,
        patternProperties_1.default
      ];
      return draft2020 ? applicator.push(prefixItems_1.default, items2020_1.default) : applicator.push(additionalItems_1.default, items_1.default), applicator.push(contains_1.default), applicator;
    }
    exports.default = getApplicator;
  }
});

// node_modules/ajv/dist/vocabularies/format/format.js
var require_format = __commonJS({
  "node_modules/ajv/dist/vocabularies/format/format.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), error2 = {
      message: ({ schemaCode }) => (0, codegen_1.str)`must match format "${schemaCode}"`,
      params: ({ schemaCode }) => (0, codegen_1._)`{format: ${schemaCode}}`
    }, def = {
      keyword: "format",
      type: ["number", "string"],
      schemaType: "string",
      $data: !0,
      error: error2,
      code(cxt, ruleType) {
        let { gen, data, $data, schema, schemaCode, it } = cxt, { opts, errSchemaPath, schemaEnv, self } = it;
        if (!opts.validateFormats)
          return;
        $data ? validate$DataFormat() : validateFormat();
        function validate$DataFormat() {
          let fmts = gen.scopeValue("formats", {
            ref: self.formats,
            code: opts.code.formats
          }), fDef = gen.const("fDef", (0, codegen_1._)`${fmts}[${schemaCode}]`), fType = gen.let("fType"), format = gen.let("format");
          gen.if((0, codegen_1._)`typeof ${fDef} == "object" && !(${fDef} instanceof RegExp)`, () => gen.assign(fType, (0, codegen_1._)`${fDef}.type || "string"`).assign(format, (0, codegen_1._)`${fDef}.validate`), () => gen.assign(fType, (0, codegen_1._)`"string"`).assign(format, fDef)), cxt.fail$data((0, codegen_1.or)(unknownFmt(), invalidFmt()));
          function unknownFmt() {
            return opts.strictSchema === !1 ? codegen_1.nil : (0, codegen_1._)`${schemaCode} && !${format}`;
          }
          function invalidFmt() {
            let callFormat = schemaEnv.$async ? (0, codegen_1._)`(${fDef}.async ? await ${format}(${data}) : ${format}(${data}))` : (0, codegen_1._)`${format}(${data})`, validData = (0, codegen_1._)`(typeof ${format} == "function" ? ${callFormat} : ${format}.test(${data}))`;
            return (0, codegen_1._)`${format} && ${format} !== true && ${fType} === ${ruleType} && !${validData}`;
          }
        }
        function validateFormat() {
          let formatDef = self.formats[schema];
          if (!formatDef) {
            unknownFormat();
            return;
          }
          if (formatDef === !0)
            return;
          let [fmtType, format, fmtRef] = getFormat(formatDef);
          fmtType === ruleType && cxt.pass(validCondition());
          function unknownFormat() {
            if (opts.strictSchema === !1) {
              self.logger.warn(unknownMsg());
              return;
            }
            throw new Error(unknownMsg());
            function unknownMsg() {
              return `unknown format "${schema}" ignored in schema at path "${errSchemaPath}"`;
            }
          }
          function getFormat(fmtDef) {
            let code = fmtDef instanceof RegExp ? (0, codegen_1.regexpCode)(fmtDef) : opts.code.formats ? (0, codegen_1._)`${opts.code.formats}${(0, codegen_1.getProperty)(schema)}` : void 0, fmt = gen.scopeValue("formats", { key: schema, ref: fmtDef, code });
            return typeof fmtDef == "object" && !(fmtDef instanceof RegExp) ? [fmtDef.type || "string", fmtDef.validate, (0, codegen_1._)`${fmt}.validate`] : ["string", fmtDef, fmt];
          }
          function validCondition() {
            if (typeof formatDef == "object" && !(formatDef instanceof RegExp) && formatDef.async) {
              if (!schemaEnv.$async)
                throw new Error("async format in sync schema");
              return (0, codegen_1._)`await ${fmtRef}(${data})`;
            }
            return typeof format == "function" ? (0, codegen_1._)`${fmtRef}(${data})` : (0, codegen_1._)`${fmtRef}.test(${data})`;
          }
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/vocabularies/format/index.js
var require_format2 = __commonJS({
  "node_modules/ajv/dist/vocabularies/format/index.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var format_1 = require_format(), format = [format_1.default];
    exports.default = format;
  }
});

// node_modules/ajv/dist/vocabularies/metadata.js
var require_metadata = __commonJS({
  "node_modules/ajv/dist/vocabularies/metadata.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.contentVocabulary = exports.metadataVocabulary = void 0;
    exports.metadataVocabulary = [
      "title",
      "description",
      "default",
      "deprecated",
      "readOnly",
      "writeOnly",
      "examples"
    ];
    exports.contentVocabulary = [
      "contentMediaType",
      "contentEncoding",
      "contentSchema"
    ];
  }
});

// node_modules/ajv/dist/vocabularies/draft7.js
var require_draft7 = __commonJS({
  "node_modules/ajv/dist/vocabularies/draft7.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var core_1 = require_core2(), validation_1 = require_validation(), applicator_1 = require_applicator(), format_1 = require_format2(), metadata_1 = require_metadata(), draft7Vocabularies = [
      core_1.default,
      validation_1.default,
      (0, applicator_1.default)(),
      format_1.default,
      metadata_1.metadataVocabulary,
      metadata_1.contentVocabulary
    ];
    exports.default = draft7Vocabularies;
  }
});

// node_modules/ajv/dist/vocabularies/discriminator/types.js
var require_types = __commonJS({
  "node_modules/ajv/dist/vocabularies/discriminator/types.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.DiscrError = void 0;
    var DiscrError;
    (function(DiscrError2) {
      DiscrError2.Tag = "tag", DiscrError2.Mapping = "mapping";
    })(DiscrError || (exports.DiscrError = DiscrError = {}));
  }
});

// node_modules/ajv/dist/vocabularies/discriminator/index.js
var require_discriminator = __commonJS({
  "node_modules/ajv/dist/vocabularies/discriminator/index.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var codegen_1 = require_codegen(), types_1 = require_types(), compile_1 = require_compile(), ref_error_1 = require_ref_error(), util_1 = require_util(), error2 = {
      message: ({ params: { discrError, tagName } }) => discrError === types_1.DiscrError.Tag ? `tag "${tagName}" must be string` : `value of tag "${tagName}" must be in oneOf`,
      params: ({ params: { discrError, tag, tagName } }) => (0, codegen_1._)`{error: ${discrError}, tag: ${tagName}, tagValue: ${tag}}`
    }, def = {
      keyword: "discriminator",
      type: "object",
      schemaType: "object",
      error: error2,
      code(cxt) {
        let { gen, data, schema, parentSchema, it } = cxt, { oneOf } = parentSchema;
        if (!it.opts.discriminator)
          throw new Error("discriminator: requires discriminator option");
        let tagName = schema.propertyName;
        if (typeof tagName != "string")
          throw new Error("discriminator: requires propertyName");
        if (schema.mapping)
          throw new Error("discriminator: mapping is not supported");
        if (!oneOf)
          throw new Error("discriminator: requires oneOf keyword");
        let valid = gen.let("valid", !1), tag = gen.const("tag", (0, codegen_1._)`${data}${(0, codegen_1.getProperty)(tagName)}`);
        gen.if((0, codegen_1._)`typeof ${tag} == "string"`, () => validateMapping(), () => cxt.error(!1, { discrError: types_1.DiscrError.Tag, tag, tagName })), cxt.ok(valid);
        function validateMapping() {
          let mapping = getMapping();
          gen.if(!1);
          for (let tagValue in mapping)
            gen.elseIf((0, codegen_1._)`${tag} === ${tagValue}`), gen.assign(valid, applyTagSchema(mapping[tagValue]));
          gen.else(), cxt.error(!1, { discrError: types_1.DiscrError.Mapping, tag, tagName }), gen.endIf();
        }
        function applyTagSchema(schemaProp) {
          let _valid = gen.name("valid"), schCxt = cxt.subschema({ keyword: "oneOf", schemaProp }, _valid);
          return cxt.mergeEvaluated(schCxt, codegen_1.Name), _valid;
        }
        function getMapping() {
          var _a;
          let oneOfMapping = {}, topRequired = hasRequired(parentSchema), tagRequired = !0;
          for (let i = 0; i < oneOf.length; i++) {
            let sch = oneOf[i];
            if (sch?.$ref && !(0, util_1.schemaHasRulesButRef)(sch, it.self.RULES)) {
              let ref2 = sch.$ref;
              if (sch = compile_1.resolveRef.call(it.self, it.schemaEnv.root, it.baseId, ref2), sch instanceof compile_1.SchemaEnv && (sch = sch.schema), sch === void 0)
                throw new ref_error_1.default(it.opts.uriResolver, it.baseId, ref2);
            }
            let propSch = (_a = sch?.properties) === null || _a === void 0 ? void 0 : _a[tagName];
            if (typeof propSch != "object")
              throw new Error(`discriminator: oneOf subschemas (or referenced schemas) must have "properties/${tagName}"`);
            tagRequired = tagRequired && (topRequired || hasRequired(sch)), addMappings(propSch, i);
          }
          if (!tagRequired)
            throw new Error(`discriminator: "${tagName}" must be required`);
          return oneOfMapping;
          function hasRequired({ required: required2 }) {
            return Array.isArray(required2) && required2.includes(tagName);
          }
          function addMappings(sch, i) {
            if (sch.const)
              addMapping(sch.const, i);
            else if (sch.enum)
              for (let tagValue of sch.enum)
                addMapping(tagValue, i);
            else
              throw new Error(`discriminator: "properties/${tagName}" must have "const" or "enum"`);
          }
          function addMapping(tagValue, i) {
            if (typeof tagValue != "string" || tagValue in oneOfMapping)
              throw new Error(`discriminator: "${tagName}" values must be unique strings`);
            oneOfMapping[tagValue] = i;
          }
        }
      }
    };
    exports.default = def;
  }
});

// node_modules/ajv/dist/refs/json-schema-draft-07.json
var require_json_schema_draft_07 = __commonJS({
  "node_modules/ajv/dist/refs/json-schema-draft-07.json"(exports, module) {
    module.exports = {
      $schema: "http://json-schema.org/draft-07/schema#",
      $id: "http://json-schema.org/draft-07/schema#",
      title: "Core schema meta-schema",
      definitions: {
        schemaArray: {
          type: "array",
          minItems: 1,
          items: { $ref: "#" }
        },
        nonNegativeInteger: {
          type: "integer",
          minimum: 0
        },
        nonNegativeIntegerDefault0: {
          allOf: [{ $ref: "#/definitions/nonNegativeInteger" }, { default: 0 }]
        },
        simpleTypes: {
          enum: ["array", "boolean", "integer", "null", "number", "object", "string"]
        },
        stringArray: {
          type: "array",
          items: { type: "string" },
          uniqueItems: !0,
          default: []
        }
      },
      type: ["object", "boolean"],
      properties: {
        $id: {
          type: "string",
          format: "uri-reference"
        },
        $schema: {
          type: "string",
          format: "uri"
        },
        $ref: {
          type: "string",
          format: "uri-reference"
        },
        $comment: {
          type: "string"
        },
        title: {
          type: "string"
        },
        description: {
          type: "string"
        },
        default: !0,
        readOnly: {
          type: "boolean",
          default: !1
        },
        examples: {
          type: "array",
          items: !0
        },
        multipleOf: {
          type: "number",
          exclusiveMinimum: 0
        },
        maximum: {
          type: "number"
        },
        exclusiveMaximum: {
          type: "number"
        },
        minimum: {
          type: "number"
        },
        exclusiveMinimum: {
          type: "number"
        },
        maxLength: { $ref: "#/definitions/nonNegativeInteger" },
        minLength: { $ref: "#/definitions/nonNegativeIntegerDefault0" },
        pattern: {
          type: "string",
          format: "regex"
        },
        additionalItems: { $ref: "#" },
        items: {
          anyOf: [{ $ref: "#" }, { $ref: "#/definitions/schemaArray" }],
          default: !0
        },
        maxItems: { $ref: "#/definitions/nonNegativeInteger" },
        minItems: { $ref: "#/definitions/nonNegativeIntegerDefault0" },
        uniqueItems: {
          type: "boolean",
          default: !1
        },
        contains: { $ref: "#" },
        maxProperties: { $ref: "#/definitions/nonNegativeInteger" },
        minProperties: { $ref: "#/definitions/nonNegativeIntegerDefault0" },
        required: { $ref: "#/definitions/stringArray" },
        additionalProperties: { $ref: "#" },
        definitions: {
          type: "object",
          additionalProperties: { $ref: "#" },
          default: {}
        },
        properties: {
          type: "object",
          additionalProperties: { $ref: "#" },
          default: {}
        },
        patternProperties: {
          type: "object",
          additionalProperties: { $ref: "#" },
          propertyNames: { format: "regex" },
          default: {}
        },
        dependencies: {
          type: "object",
          additionalProperties: {
            anyOf: [{ $ref: "#" }, { $ref: "#/definitions/stringArray" }]
          }
        },
        propertyNames: { $ref: "#" },
        const: !0,
        enum: {
          type: "array",
          items: !0,
          minItems: 1,
          uniqueItems: !0
        },
        type: {
          anyOf: [
            { $ref: "#/definitions/simpleTypes" },
            {
              type: "array",
              items: { $ref: "#/definitions/simpleTypes" },
              minItems: 1,
              uniqueItems: !0
            }
          ]
        },
        format: { type: "string" },
        contentMediaType: { type: "string" },
        contentEncoding: { type: "string" },
        if: { $ref: "#" },
        then: { $ref: "#" },
        else: { $ref: "#" },
        allOf: { $ref: "#/definitions/schemaArray" },
        anyOf: { $ref: "#/definitions/schemaArray" },
        oneOf: { $ref: "#/definitions/schemaArray" },
        not: { $ref: "#" }
      },
      default: !0
    };
  }
});

// node_modules/ajv/dist/ajv.js
var require_ajv = __commonJS({
  "node_modules/ajv/dist/ajv.js"(exports, module) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.MissingRefError = exports.ValidationError = exports.CodeGen = exports.Name = exports.nil = exports.stringify = exports.str = exports._ = exports.KeywordCxt = exports.Ajv = void 0;
    var core_1 = require_core(), draft7_1 = require_draft7(), discriminator_1 = require_discriminator(), draft7MetaSchema = require_json_schema_draft_07(), META_SUPPORT_DATA = ["/properties"], META_SCHEMA_ID = "http://json-schema.org/draft-07/schema", Ajv2 = class extends core_1.default {
      _addVocabularies() {
        super._addVocabularies(), draft7_1.default.forEach((v) => this.addVocabulary(v)), this.opts.discriminator && this.addKeyword(discriminator_1.default);
      }
      _addDefaultMetaSchema() {
        if (super._addDefaultMetaSchema(), !this.opts.meta)
          return;
        let metaSchema = this.opts.$data ? this.$dataMetaSchema(draft7MetaSchema, META_SUPPORT_DATA) : draft7MetaSchema;
        this.addMetaSchema(metaSchema, META_SCHEMA_ID, !1), this.refs["http://json-schema.org/schema"] = META_SCHEMA_ID;
      }
      defaultMeta() {
        return this.opts.defaultMeta = super.defaultMeta() || (this.getSchema(META_SCHEMA_ID) ? META_SCHEMA_ID : void 0);
      }
    };
    exports.Ajv = Ajv2;
    module.exports = exports = Ajv2;
    module.exports.Ajv = Ajv2;
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.default = Ajv2;
    var validate_1 = require_validate();
    Object.defineProperty(exports, "KeywordCxt", { enumerable: !0, get: function() {
      return validate_1.KeywordCxt;
    } });
    var codegen_1 = require_codegen();
    Object.defineProperty(exports, "_", { enumerable: !0, get: function() {
      return codegen_1._;
    } });
    Object.defineProperty(exports, "str", { enumerable: !0, get: function() {
      return codegen_1.str;
    } });
    Object.defineProperty(exports, "stringify", { enumerable: !0, get: function() {
      return codegen_1.stringify;
    } });
    Object.defineProperty(exports, "nil", { enumerable: !0, get: function() {
      return codegen_1.nil;
    } });
    Object.defineProperty(exports, "Name", { enumerable: !0, get: function() {
      return codegen_1.Name;
    } });
    Object.defineProperty(exports, "CodeGen", { enumerable: !0, get: function() {
      return codegen_1.CodeGen;
    } });
    var validation_error_1 = require_validation_error();
    Object.defineProperty(exports, "ValidationError", { enumerable: !0, get: function() {
      return validation_error_1.default;
    } });
    var ref_error_1 = require_ref_error();
    Object.defineProperty(exports, "MissingRefError", { enumerable: !0, get: function() {
      return ref_error_1.default;
    } });
  }
});

// node_modules/ajv-formats/dist/formats.js
var require_formats = __commonJS({
  "node_modules/ajv-formats/dist/formats.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.formatNames = exports.fastFormats = exports.fullFormats = void 0;
    function fmtDef(validate, compare) {
      return { validate, compare };
    }
    exports.fullFormats = {
      // date: http://tools.ietf.org/html/rfc3339#section-5.6
      date: fmtDef(date3, compareDate),
      // date-time: http://tools.ietf.org/html/rfc3339#section-5.6
      time: fmtDef(getTime(!0), compareTime),
      "date-time": fmtDef(getDateTime(!0), compareDateTime),
      "iso-time": fmtDef(getTime(), compareIsoTime),
      "iso-date-time": fmtDef(getDateTime(), compareIsoDateTime),
      // duration: https://tools.ietf.org/html/rfc3339#appendix-A
      duration: /^P(?!$)((\d+Y)?(\d+M)?(\d+D)?(T(?=\d)(\d+H)?(\d+M)?(\d+S)?)?|(\d+W)?)$/,
      uri,
      "uri-reference": /^(?:[a-z][a-z0-9+\-.]*:)?(?:\/?\/(?:(?:[a-z0-9\-._~!$&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\[(?:(?:(?:(?:[0-9a-f]{1,4}:){6}|::(?:[0-9a-f]{1,4}:){5}|(?:[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){4}|(?:(?:[0-9a-f]{1,4}:){0,1}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){3}|(?:(?:[0-9a-f]{1,4}:){0,2}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){2}|(?:(?:[0-9a-f]{1,4}:){0,3}[0-9a-f]{1,4})?::[0-9a-f]{1,4}:|(?:(?:[0-9a-f]{1,4}:){0,4}[0-9a-f]{1,4})?::)(?:[0-9a-f]{1,4}:[0-9a-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?))|(?:(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})?::[0-9a-f]{1,4}|(?:(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{1,4})?::)|[Vv][0-9a-f]+\.[a-z0-9\-._~!$&'()*+,;=:]+)\]|(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)|(?:[a-z0-9\-._~!$&'"()*+,;=]|%[0-9a-f]{2})*)(?::\d*)?(?:\/(?:[a-z0-9\-._~!$&'"()*+,;=:@]|%[0-9a-f]{2})*)*|\/(?:(?:[a-z0-9\-._~!$&'"()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[a-z0-9\-._~!$&'"()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[a-z0-9\-._~!$&'"()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[a-z0-9\-._~!$&'"()*+,;=:@]|%[0-9a-f]{2})*)*)?(?:\?(?:[a-z0-9\-._~!$&'"()*+,;=:@/?]|%[0-9a-f]{2})*)?(?:#(?:[a-z0-9\-._~!$&'"()*+,;=:@/?]|%[0-9a-f]{2})*)?$/i,
      // uri-template: https://tools.ietf.org/html/rfc6570
      "uri-template": /^(?:(?:[^\x00-\x20"'<>%\\^`{|}]|%[0-9a-f]{2})|\{[+#./;?&=,!@|]?(?:[a-z0-9_]|%[0-9a-f]{2})+(?::[1-9][0-9]{0,3}|\*)?(?:,(?:[a-z0-9_]|%[0-9a-f]{2})+(?::[1-9][0-9]{0,3}|\*)?)*\})*$/i,
      // For the source: https://gist.github.com/dperini/729294
      // For test cases: https://mathiasbynens.be/demo/url-regex
      url: /^(?:https?|ftp):\/\/(?:\S+(?::\S*)?@)?(?:(?!(?:10|127)(?:\.\d{1,3}){3})(?!(?:169\.254|192\.168)(?:\.\d{1,3}){2})(?!172\.(?:1[6-9]|2\d|3[0-1])(?:\.\d{1,3}){2})(?:[1-9]\d?|1\d\d|2[01]\d|22[0-3])(?:\.(?:1?\d{1,2}|2[0-4]\d|25[0-5])){2}(?:\.(?:[1-9]\d?|1\d\d|2[0-4]\d|25[0-4]))|(?:(?:[a-z0-9\u{00a1}-\u{ffff}]+-)*[a-z0-9\u{00a1}-\u{ffff}]+)(?:\.(?:[a-z0-9\u{00a1}-\u{ffff}]+-)*[a-z0-9\u{00a1}-\u{ffff}]+)*(?:\.(?:[a-z\u{00a1}-\u{ffff}]{2,})))(?::\d{2,5})?(?:\/[^\s]*)?$/iu,
      email: /^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i,
      hostname: /^(?=.{1,253}\.?$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[-0-9a-z]{0,61}[0-9a-z])?)*\.?$/i,
      // optimized https://www.safaribooksonline.com/library/view/regular-expressions-cookbook/9780596802837/ch07s16.html
      ipv4: /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/,
      ipv6: /^((([0-9a-f]{1,4}:){7}([0-9a-f]{1,4}|:))|(([0-9a-f]{1,4}:){6}(:[0-9a-f]{1,4}|((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9a-f]{1,4}:){5}(((:[0-9a-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9a-f]{1,4}:){4}(((:[0-9a-f]{1,4}){1,3})|((:[0-9a-f]{1,4})?:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-f]{1,4}:){3}(((:[0-9a-f]{1,4}){1,4})|((:[0-9a-f]{1,4}){0,2}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-f]{1,4}:){2}(((:[0-9a-f]{1,4}){1,5})|((:[0-9a-f]{1,4}){0,3}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-f]{1,4}:){1}(((:[0-9a-f]{1,4}){1,6})|((:[0-9a-f]{1,4}){0,4}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(:(((:[0-9a-f]{1,4}){1,7})|((:[0-9a-f]{1,4}){0,5}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:)))$/i,
      regex,
      // uuid: http://tools.ietf.org/html/rfc4122
      uuid: /^(?:urn:uuid:)?[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i,
      // JSON-pointer: https://tools.ietf.org/html/rfc6901
      // uri fragment: https://tools.ietf.org/html/rfc3986#appendix-A
      "json-pointer": /^(?:\/(?:[^~/]|~0|~1)*)*$/,
      "json-pointer-uri-fragment": /^#(?:\/(?:[a-z0-9_\-.!$&'()*+,;:=@]|%[0-9a-f]{2}|~0|~1)*)*$/i,
      // relative JSON-pointer: http://tools.ietf.org/html/draft-luff-relative-json-pointer-00
      "relative-json-pointer": /^(?:0|[1-9][0-9]*)(?:#|(?:\/(?:[^~/]|~0|~1)*)*)$/,
      // the following formats are used by the openapi specification: https://spec.openapis.org/oas/v3.0.0#data-types
      // byte: https://github.com/miguelmota/is-base64
      byte,
      // signed 32 bit integer
      int32: { type: "number", validate: validateInt32 },
      // signed 64 bit integer
      int64: { type: "number", validate: validateInt64 },
      // C-type float
      float: { type: "number", validate: validateNumber },
      // C-type double
      double: { type: "number", validate: validateNumber },
      // hint to the UI to hide input strings
      password: !0,
      // unchecked string payload
      binary: !0
    };
    exports.fastFormats = {
      ...exports.fullFormats,
      date: fmtDef(/^\d\d\d\d-[0-1]\d-[0-3]\d$/, compareDate),
      time: fmtDef(/^(?:[0-2]\d:[0-5]\d:[0-5]\d|23:59:60)(?:\.\d+)?(?:z|[+-]\d\d(?::?\d\d)?)$/i, compareTime),
      "date-time": fmtDef(/^\d\d\d\d-[0-1]\d-[0-3]\dt(?:[0-2]\d:[0-5]\d:[0-5]\d|23:59:60)(?:\.\d+)?(?:z|[+-]\d\d(?::?\d\d)?)$/i, compareDateTime),
      "iso-time": fmtDef(/^(?:[0-2]\d:[0-5]\d:[0-5]\d|23:59:60)(?:\.\d+)?(?:z|[+-]\d\d(?::?\d\d)?)?$/i, compareIsoTime),
      "iso-date-time": fmtDef(/^\d\d\d\d-[0-1]\d-[0-3]\d[t\s](?:[0-2]\d:[0-5]\d:[0-5]\d|23:59:60)(?:\.\d+)?(?:z|[+-]\d\d(?::?\d\d)?)?$/i, compareIsoDateTime),
      // uri: https://github.com/mafintosh/is-my-json-valid/blob/master/formats.js
      uri: /^(?:[a-z][a-z0-9+\-.]*:)(?:\/?\/)?[^\s]*$/i,
      "uri-reference": /^(?:(?:[a-z][a-z0-9+\-.]*:)?\/?\/)?(?:[^\\\s#][^\s#]*)?(?:#[^\\\s]*)?$/i,
      // email (sources from jsen validator):
      // http://stackoverflow.com/questions/201323/using-a-regular-expression-to-validate-an-email-address#answer-8829363
      // http://www.w3.org/TR/html5/forms.html#valid-e-mail-address (search for 'wilful violation')
      email: /^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$/i
    };
    exports.formatNames = Object.keys(exports.fullFormats);
    function isLeapYear(year) {
      return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
    }
    var DATE = /^(\d\d\d\d)-(\d\d)-(\d\d)$/, DAYS = [0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    function date3(str) {
      let matches = DATE.exec(str);
      if (!matches)
        return !1;
      let year = +matches[1], month = +matches[2], day = +matches[3];
      return month >= 1 && month <= 12 && day >= 1 && day <= (month === 2 && isLeapYear(year) ? 29 : DAYS[month]);
    }
    function compareDate(d1, d2) {
      if (d1 && d2)
        return d1 > d2 ? 1 : d1 < d2 ? -1 : 0;
    }
    var TIME = /^(\d\d):(\d\d):(\d\d(?:\.\d+)?)(z|([+-])(\d\d)(?::?(\d\d))?)?$/i;
    function getTime(strictTimeZone) {
      return function(str) {
        let matches = TIME.exec(str);
        if (!matches)
          return !1;
        let hr = +matches[1], min = +matches[2], sec = +matches[3], tz = matches[4], tzSign = matches[5] === "-" ? -1 : 1, tzH = +(matches[6] || 0), tzM = +(matches[7] || 0);
        if (tzH > 23 || tzM > 59 || strictTimeZone && !tz)
          return !1;
        if (hr <= 23 && min <= 59 && sec < 60)
          return !0;
        let utcMin = min - tzM * tzSign, utcHr = hr - tzH * tzSign - (utcMin < 0 ? 1 : 0);
        return (utcHr === 23 || utcHr === -1) && (utcMin === 59 || utcMin === -1) && sec < 61;
      };
    }
    function compareTime(s1, s2) {
      if (!(s1 && s2))
        return;
      let t1 = (/* @__PURE__ */ new Date("2020-01-01T" + s1)).valueOf(), t2 = (/* @__PURE__ */ new Date("2020-01-01T" + s2)).valueOf();
      if (t1 && t2)
        return t1 - t2;
    }
    function compareIsoTime(t1, t2) {
      if (!(t1 && t2))
        return;
      let a1 = TIME.exec(t1), a2 = TIME.exec(t2);
      if (a1 && a2)
        return t1 = a1[1] + a1[2] + a1[3], t2 = a2[1] + a2[2] + a2[3], t1 > t2 ? 1 : t1 < t2 ? -1 : 0;
    }
    var DATE_TIME_SEPARATOR = /t|\s/i;
    function getDateTime(strictTimeZone) {
      let time3 = getTime(strictTimeZone);
      return function(str) {
        let dateTime = str.split(DATE_TIME_SEPARATOR);
        return dateTime.length === 2 && date3(dateTime[0]) && time3(dateTime[1]);
      };
    }
    function compareDateTime(dt1, dt2) {
      if (!(dt1 && dt2))
        return;
      let d1 = new Date(dt1).valueOf(), d2 = new Date(dt2).valueOf();
      if (d1 && d2)
        return d1 - d2;
    }
    function compareIsoDateTime(dt1, dt2) {
      if (!(dt1 && dt2))
        return;
      let [d1, t1] = dt1.split(DATE_TIME_SEPARATOR), [d2, t2] = dt2.split(DATE_TIME_SEPARATOR), res = compareDate(d1, d2);
      if (res !== void 0)
        return res || compareTime(t1, t2);
    }
    var NOT_URI_FRAGMENT = /\/|:/, URI = /^(?:[a-z][a-z0-9+\-.]*:)(?:\/?\/(?:(?:[a-z0-9\-._~!$&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\[(?:(?:(?:(?:[0-9a-f]{1,4}:){6}|::(?:[0-9a-f]{1,4}:){5}|(?:[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){4}|(?:(?:[0-9a-f]{1,4}:){0,1}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){3}|(?:(?:[0-9a-f]{1,4}:){0,2}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){2}|(?:(?:[0-9a-f]{1,4}:){0,3}[0-9a-f]{1,4})?::[0-9a-f]{1,4}:|(?:(?:[0-9a-f]{1,4}:){0,4}[0-9a-f]{1,4})?::)(?:[0-9a-f]{1,4}:[0-9a-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?))|(?:(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})?::[0-9a-f]{1,4}|(?:(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{1,4})?::)|[Vv][0-9a-f]+\.[a-z0-9\-._~!$&'()*+,;=:]+)\]|(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)|(?:[a-z0-9\-._~!$&'()*+,;=]|%[0-9a-f]{2})*)(?::\d*)?(?:\/(?:[a-z0-9\-._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*|\/(?:(?:[a-z0-9\-._~!$&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[a-z0-9\-._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[a-z0-9\-._~!$&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[a-z0-9\-._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*)(?:\?(?:[a-z0-9\-._~!$&'()*+,;=:@/?]|%[0-9a-f]{2})*)?(?:#(?:[a-z0-9\-._~!$&'()*+,;=:@/?]|%[0-9a-f]{2})*)?$/i;
    function uri(str) {
      return NOT_URI_FRAGMENT.test(str) && URI.test(str);
    }
    var BYTE = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/gm;
    function byte(str) {
      return BYTE.lastIndex = 0, BYTE.test(str);
    }
    var MIN_INT32 = -(2 ** 31), MAX_INT32 = 2 ** 31 - 1;
    function validateInt32(value) {
      return Number.isInteger(value) && value <= MAX_INT32 && value >= MIN_INT32;
    }
    function validateInt64(value) {
      return Number.isInteger(value);
    }
    function validateNumber() {
      return !0;
    }
    var Z_ANCHOR = /[^\\]\\Z/;
    function regex(str) {
      if (Z_ANCHOR.test(str))
        return !1;
      try {
        return new RegExp(str), !0;
      } catch {
        return !1;
      }
    }
  }
});

// node_modules/ajv-formats/dist/limit.js
var require_limit = __commonJS({
  "node_modules/ajv-formats/dist/limit.js"(exports) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.formatLimitDefinition = void 0;
    var ajv_1 = require_ajv(), codegen_1 = require_codegen(), ops = codegen_1.operators, KWDs = {
      formatMaximum: { okStr: "<=", ok: ops.LTE, fail: ops.GT },
      formatMinimum: { okStr: ">=", ok: ops.GTE, fail: ops.LT },
      formatExclusiveMaximum: { okStr: "<", ok: ops.LT, fail: ops.GTE },
      formatExclusiveMinimum: { okStr: ">", ok: ops.GT, fail: ops.LTE }
    }, error2 = {
      message: ({ keyword, schemaCode }) => (0, codegen_1.str)`should be ${KWDs[keyword].okStr} ${schemaCode}`,
      params: ({ keyword, schemaCode }) => (0, codegen_1._)`{comparison: ${KWDs[keyword].okStr}, limit: ${schemaCode}}`
    };
    exports.formatLimitDefinition = {
      keyword: Object.keys(KWDs),
      type: "string",
      schemaType: "string",
      $data: !0,
      error: error2,
      code(cxt) {
        let { gen, data, schemaCode, keyword, it } = cxt, { opts, self } = it;
        if (!opts.validateFormats)
          return;
        let fCxt = new ajv_1.KeywordCxt(it, self.RULES.all.format.definition, "format");
        fCxt.$data ? validate$DataFormat() : validateFormat();
        function validate$DataFormat() {
          let fmts = gen.scopeValue("formats", {
            ref: self.formats,
            code: opts.code.formats
          }), fmt = gen.const("fmt", (0, codegen_1._)`${fmts}[${fCxt.schemaCode}]`);
          cxt.fail$data((0, codegen_1.or)((0, codegen_1._)`typeof ${fmt} != "object"`, (0, codegen_1._)`${fmt} instanceof RegExp`, (0, codegen_1._)`typeof ${fmt}.compare != "function"`, compareCode(fmt)));
        }
        function validateFormat() {
          let format = fCxt.schema, fmtDef = self.formats[format];
          if (!fmtDef || fmtDef === !0)
            return;
          if (typeof fmtDef != "object" || fmtDef instanceof RegExp || typeof fmtDef.compare != "function")
            throw new Error(`"${keyword}": format "${format}" does not define "compare" function`);
          let fmt = gen.scopeValue("formats", {
            key: format,
            ref: fmtDef,
            code: opts.code.formats ? (0, codegen_1._)`${opts.code.formats}${(0, codegen_1.getProperty)(format)}` : void 0
          });
          cxt.fail$data(compareCode(fmt));
        }
        function compareCode(fmt) {
          return (0, codegen_1._)`${fmt}.compare(${data}, ${schemaCode}) ${KWDs[keyword].fail} 0`;
        }
      },
      dependencies: ["format"]
    };
    var formatLimitPlugin = (ajv) => (ajv.addKeyword(exports.formatLimitDefinition), ajv);
    exports.default = formatLimitPlugin;
  }
});

// node_modules/ajv-formats/dist/index.js
var require_dist = __commonJS({
  "node_modules/ajv-formats/dist/index.js"(exports, module) {
    "use strict";
    Object.defineProperty(exports, "__esModule", { value: !0 });
    var formats_1 = require_formats(), limit_1 = require_limit(), codegen_1 = require_codegen(), fullName = new codegen_1.Name("fullFormats"), fastName = new codegen_1.Name("fastFormats"), formatsPlugin = (ajv, opts = { keywords: !0 }) => {
      if (Array.isArray(opts))
        return addFormats(ajv, opts, formats_1.fullFormats, fullName), ajv;
      let [formats, exportName] = opts.mode === "fast" ? [formats_1.fastFormats, fastName] : [formats_1.fullFormats, fullName], list = opts.formats || formats_1.formatNames;
      return addFormats(ajv, list, formats, exportName), opts.keywords && (0, limit_1.default)(ajv), ajv;
    };
    formatsPlugin.get = (name, mode = "full") => {
      let f = (mode === "fast" ? formats_1.fastFormats : formats_1.fullFormats)[name];
      if (!f)
        throw new Error(`Unknown format "${name}"`);
      return f;
    };
    function addFormats(ajv, list, fs2, exportName) {
      var _a, _b;
      (_a = (_b = ajv.opts.code).formats) !== null && _a !== void 0 || (_b.formats = (0, codegen_1._)`require("ajv-formats/dist/formats").${exportName}`);
      for (let f of list)
        ajv.addFormat(f, fs2[f]);
    }
    module.exports = exports = formatsPlugin;
    Object.defineProperty(exports, "__esModule", { value: !0 });
    exports.default = formatsPlugin;
  }
});

// node_modules/content-type/index.js
var require_content_type = __commonJS({
  "node_modules/content-type/index.js"(exports) {
    "use strict";
    var PARAM_REGEXP = /; *([!#$%&'*+.^_`|~0-9A-Za-z-]+) *= *("(?:[\u000b\u0020\u0021\u0023-\u005b\u005d-\u007e\u0080-\u00ff]|\\[\u000b\u0020-\u00ff])*"|[!#$%&'*+.^_`|~0-9A-Za-z-]+) */g, TEXT_REGEXP = /^[\u000b\u0020-\u007e\u0080-\u00ff]+$/, TOKEN_REGEXP = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/, QESC_REGEXP = /\\([\u000b\u0020-\u00ff])/g, QUOTE_REGEXP = /([\\"])/g, TYPE_REGEXP = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+\/[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
    exports.format = format;
    exports.parse = parse3;
    function format(obj) {
      if (!obj || typeof obj != "object")
        throw new TypeError("argument obj is required");
      var parameters = obj.parameters, type = obj.type;
      if (!type || !TYPE_REGEXP.test(type))
        throw new TypeError("invalid type");
      var string3 = type;
      if (parameters && typeof parameters == "object")
        for (var param, params = Object.keys(parameters).sort(), i = 0; i < params.length; i++) {
          if (param = params[i], !TOKEN_REGEXP.test(param))
            throw new TypeError("invalid parameter name");
          string3 += "; " + param + "=" + qstring(parameters[param]);
        }
      return string3;
    }
    function parse3(string3) {
      if (!string3)
        throw new TypeError("argument string is required");
      var header = typeof string3 == "object" ? getcontenttype(string3) : string3;
      if (typeof header != "string")
        throw new TypeError("argument string is required to be a string");
      var index = header.indexOf(";"), type = index !== -1 ? header.slice(0, index).trim() : header.trim();
      if (!TYPE_REGEXP.test(type))
        throw new TypeError("invalid media type");
      var obj = new ContentType(type.toLowerCase());
      if (index !== -1) {
        var key, match, value;
        for (PARAM_REGEXP.lastIndex = index; match = PARAM_REGEXP.exec(header); ) {
          if (match.index !== index)
            throw new TypeError("invalid parameter format");
          index += match[0].length, key = match[1].toLowerCase(), value = match[2], value.charCodeAt(0) === 34 && (value = value.slice(1, -1), value.indexOf("\\") !== -1 && (value = value.replace(QESC_REGEXP, "$1"))), obj.parameters[key] = value;
        }
        if (index !== header.length)
          throw new TypeError("invalid parameter format");
      }
      return obj;
    }
    function getcontenttype(obj) {
      var header;
      if (typeof obj.getHeader == "function" ? header = obj.getHeader("content-type") : typeof obj.headers == "object" && (header = obj.headers && obj.headers["content-type"]), typeof header != "string")
        throw new TypeError("content-type header is missing from object");
      return header;
    }
    function qstring(val) {
      var str = String(val);
      if (TOKEN_REGEXP.test(str))
        return str;
      if (str.length > 0 && !TEXT_REGEXP.test(str))
        throw new TypeError("invalid parameter value");
      return '"' + str.replace(QUOTE_REGEXP, "\\$1") + '"';
    }
    function ContentType(type) {
      this.parameters = /* @__PURE__ */ Object.create(null), this.type = type;
    }
  }
});

// src/server.js
import http from "node:http";
import os from "node:os";
import path2 from "node:path";
import { timingSafeEqual } from "node:crypto";

// node_modules/zod/v3/external.js
var external_exports = {};
__export(external_exports, {
  BRAND: () => BRAND,
  DIRTY: () => DIRTY,
  EMPTY_PATH: () => EMPTY_PATH,
  INVALID: () => INVALID,
  NEVER: () => NEVER,
  OK: () => OK,
  ParseStatus: () => ParseStatus,
  Schema: () => ZodType,
  ZodAny: () => ZodAny,
  ZodArray: () => ZodArray,
  ZodBigInt: () => ZodBigInt,
  ZodBoolean: () => ZodBoolean,
  ZodBranded: () => ZodBranded,
  ZodCatch: () => ZodCatch,
  ZodDate: () => ZodDate,
  ZodDefault: () => ZodDefault,
  ZodDiscriminatedUnion: () => ZodDiscriminatedUnion,
  ZodEffects: () => ZodEffects,
  ZodEnum: () => ZodEnum,
  ZodError: () => ZodError,
  ZodFirstPartyTypeKind: () => ZodFirstPartyTypeKind,
  ZodFunction: () => ZodFunction,
  ZodIntersection: () => ZodIntersection,
  ZodIssueCode: () => ZodIssueCode,
  ZodLazy: () => ZodLazy,
  ZodLiteral: () => ZodLiteral,
  ZodMap: () => ZodMap,
  ZodNaN: () => ZodNaN,
  ZodNativeEnum: () => ZodNativeEnum,
  ZodNever: () => ZodNever,
  ZodNull: () => ZodNull,
  ZodNullable: () => ZodNullable,
  ZodNumber: () => ZodNumber,
  ZodObject: () => ZodObject,
  ZodOptional: () => ZodOptional,
  ZodParsedType: () => ZodParsedType,
  ZodPipeline: () => ZodPipeline,
  ZodPromise: () => ZodPromise,
  ZodReadonly: () => ZodReadonly,
  ZodRecord: () => ZodRecord,
  ZodSchema: () => ZodType,
  ZodSet: () => ZodSet,
  ZodString: () => ZodString,
  ZodSymbol: () => ZodSymbol,
  ZodTransformer: () => ZodEffects,
  ZodTuple: () => ZodTuple,
  ZodType: () => ZodType,
  ZodUndefined: () => ZodUndefined,
  ZodUnion: () => ZodUnion,
  ZodUnknown: () => ZodUnknown,
  ZodVoid: () => ZodVoid,
  addIssueToContext: () => addIssueToContext,
  any: () => anyType,
  array: () => arrayType,
  bigint: () => bigIntType,
  boolean: () => booleanType,
  coerce: () => coerce,
  custom: () => custom,
  date: () => dateType,
  datetimeRegex: () => datetimeRegex,
  defaultErrorMap: () => en_default,
  discriminatedUnion: () => discriminatedUnionType,
  effect: () => effectsType,
  enum: () => enumType,
  function: () => functionType,
  getErrorMap: () => getErrorMap,
  getParsedType: () => getParsedType,
  instanceof: () => instanceOfType,
  intersection: () => intersectionType,
  isAborted: () => isAborted,
  isAsync: () => isAsync,
  isDirty: () => isDirty,
  isValid: () => isValid,
  late: () => late,
  lazy: () => lazyType,
  literal: () => literalType,
  makeIssue: () => makeIssue,
  map: () => mapType,
  nan: () => nanType,
  nativeEnum: () => nativeEnumType,
  never: () => neverType,
  null: () => nullType,
  nullable: () => nullableType,
  number: () => numberType,
  object: () => objectType,
  objectUtil: () => objectUtil,
  oboolean: () => oboolean,
  onumber: () => onumber,
  optional: () => optionalType,
  ostring: () => ostring,
  pipeline: () => pipelineType,
  preprocess: () => preprocessType,
  promise: () => promiseType,
  quotelessJson: () => quotelessJson,
  record: () => recordType,
  set: () => setType,
  setErrorMap: () => setErrorMap,
  strictObject: () => strictObjectType,
  string: () => stringType,
  symbol: () => symbolType,
  transformer: () => effectsType,
  tuple: () => tupleType,
  undefined: () => undefinedType,
  union: () => unionType,
  unknown: () => unknownType,
  util: () => util,
  void: () => voidType
});

// node_modules/zod/v3/helpers/util.js
var util;
(function(util2) {
  util2.assertEqual = (_) => {
  };
  function assertIs2(_arg) {
  }
  util2.assertIs = assertIs2;
  function assertNever2(_x) {
    throw new Error();
  }
  util2.assertNever = assertNever2, util2.arrayToEnum = (items) => {
    let obj = {};
    for (let item of items)
      obj[item] = item;
    return obj;
  }, util2.getValidEnumValues = (obj) => {
    let validKeys = util2.objectKeys(obj).filter((k) => typeof obj[obj[k]] != "number"), filtered = {};
    for (let k of validKeys)
      filtered[k] = obj[k];
    return util2.objectValues(filtered);
  }, util2.objectValues = (obj) => util2.objectKeys(obj).map(function(e) {
    return obj[e];
  }), util2.objectKeys = typeof Object.keys == "function" ? (obj) => Object.keys(obj) : (object3) => {
    let keys = [];
    for (let key in object3)
      Object.prototype.hasOwnProperty.call(object3, key) && keys.push(key);
    return keys;
  }, util2.find = (arr, checker) => {
    for (let item of arr)
      if (checker(item))
        return item;
  }, util2.isInteger = typeof Number.isInteger == "function" ? (val) => Number.isInteger(val) : (val) => typeof val == "number" && Number.isFinite(val) && Math.floor(val) === val;
  function joinValues2(array2, separator = " | ") {
    return array2.map((val) => typeof val == "string" ? `'${val}'` : val).join(separator);
  }
  util2.joinValues = joinValues2, util2.jsonStringifyReplacer = (_, value) => typeof value == "bigint" ? value.toString() : value;
})(util || (util = {}));
var objectUtil;
(function(objectUtil2) {
  objectUtil2.mergeShapes = (first, second) => ({
    ...first,
    ...second
    // second overwrites first
  });
})(objectUtil || (objectUtil = {}));
var ZodParsedType = util.arrayToEnum([
  "string",
  "nan",
  "number",
  "integer",
  "float",
  "boolean",
  "date",
  "bigint",
  "symbol",
  "function",
  "undefined",
  "null",
  "array",
  "object",
  "unknown",
  "promise",
  "void",
  "never",
  "map",
  "set"
]), getParsedType = (data) => {
  switch (typeof data) {
    case "undefined":
      return ZodParsedType.undefined;
    case "string":
      return ZodParsedType.string;
    case "number":
      return Number.isNaN(data) ? ZodParsedType.nan : ZodParsedType.number;
    case "boolean":
      return ZodParsedType.boolean;
    case "function":
      return ZodParsedType.function;
    case "bigint":
      return ZodParsedType.bigint;
    case "symbol":
      return ZodParsedType.symbol;
    case "object":
      return Array.isArray(data) ? ZodParsedType.array : data === null ? ZodParsedType.null : data.then && typeof data.then == "function" && data.catch && typeof data.catch == "function" ? ZodParsedType.promise : typeof Map < "u" && data instanceof Map ? ZodParsedType.map : typeof Set < "u" && data instanceof Set ? ZodParsedType.set : typeof Date < "u" && data instanceof Date ? ZodParsedType.date : ZodParsedType.object;
    default:
      return ZodParsedType.unknown;
  }
};

// node_modules/zod/v3/ZodError.js
var ZodIssueCode = util.arrayToEnum([
  "invalid_type",
  "invalid_literal",
  "custom",
  "invalid_union",
  "invalid_union_discriminator",
  "invalid_enum_value",
  "unrecognized_keys",
  "invalid_arguments",
  "invalid_return_type",
  "invalid_date",
  "invalid_string",
  "too_small",
  "too_big",
  "invalid_intersection_types",
  "not_multiple_of",
  "not_finite"
]), quotelessJson = (obj) => JSON.stringify(obj, null, 2).replace(/"([^"]+)":/g, "$1:"), ZodError = class _ZodError extends Error {
  get errors() {
    return this.issues;
  }
  constructor(issues) {
    super(), this.issues = [], this.addIssue = (sub) => {
      this.issues = [...this.issues, sub];
    }, this.addIssues = (subs = []) => {
      this.issues = [...this.issues, ...subs];
    };
    let actualProto = new.target.prototype;
    Object.setPrototypeOf ? Object.setPrototypeOf(this, actualProto) : this.__proto__ = actualProto, this.name = "ZodError", this.issues = issues;
  }
  format(_mapper) {
    let mapper = _mapper || function(issue2) {
      return issue2.message;
    }, fieldErrors = { _errors: [] }, processError = (error2) => {
      for (let issue2 of error2.issues)
        if (issue2.code === "invalid_union")
          issue2.unionErrors.map(processError);
        else if (issue2.code === "invalid_return_type")
          processError(issue2.returnTypeError);
        else if (issue2.code === "invalid_arguments")
          processError(issue2.argumentsError);
        else if (issue2.path.length === 0)
          fieldErrors._errors.push(mapper(issue2));
        else {
          let curr = fieldErrors, i = 0;
          for (; i < issue2.path.length; ) {
            let el = issue2.path[i];
            i === issue2.path.length - 1 ? (curr[el] = curr[el] || { _errors: [] }, curr[el]._errors.push(mapper(issue2))) : curr[el] = curr[el] || { _errors: [] }, curr = curr[el], i++;
          }
        }
    };
    return processError(this), fieldErrors;
  }
  static assert(value) {
    if (!(value instanceof _ZodError))
      throw new Error(`Not a ZodError: ${value}`);
  }
  toString() {
    return this.message;
  }
  get message() {
    return JSON.stringify(this.issues, util.jsonStringifyReplacer, 2);
  }
  get isEmpty() {
    return this.issues.length === 0;
  }
  flatten(mapper = (issue2) => issue2.message) {
    let fieldErrors = {}, formErrors = [];
    for (let sub of this.issues)
      if (sub.path.length > 0) {
        let firstEl = sub.path[0];
        fieldErrors[firstEl] = fieldErrors[firstEl] || [], fieldErrors[firstEl].push(mapper(sub));
      } else
        formErrors.push(mapper(sub));
    return { formErrors, fieldErrors };
  }
  get formErrors() {
    return this.flatten();
  }
};
ZodError.create = (issues) => new ZodError(issues);

// node_modules/zod/v3/locales/en.js
var errorMap = (issue2, _ctx) => {
  let message;
  switch (issue2.code) {
    case ZodIssueCode.invalid_type:
      issue2.received === ZodParsedType.undefined ? message = "Required" : message = `Expected ${issue2.expected}, received ${issue2.received}`;
      break;
    case ZodIssueCode.invalid_literal:
      message = `Invalid literal value, expected ${JSON.stringify(issue2.expected, util.jsonStringifyReplacer)}`;
      break;
    case ZodIssueCode.unrecognized_keys:
      message = `Unrecognized key(s) in object: ${util.joinValues(issue2.keys, ", ")}`;
      break;
    case ZodIssueCode.invalid_union:
      message = "Invalid input";
      break;
    case ZodIssueCode.invalid_union_discriminator:
      message = `Invalid discriminator value. Expected ${util.joinValues(issue2.options)}`;
      break;
    case ZodIssueCode.invalid_enum_value:
      message = `Invalid enum value. Expected ${util.joinValues(issue2.options)}, received '${issue2.received}'`;
      break;
    case ZodIssueCode.invalid_arguments:
      message = "Invalid function arguments";
      break;
    case ZodIssueCode.invalid_return_type:
      message = "Invalid function return type";
      break;
    case ZodIssueCode.invalid_date:
      message = "Invalid date";
      break;
    case ZodIssueCode.invalid_string:
      typeof issue2.validation == "object" ? "includes" in issue2.validation ? (message = `Invalid input: must include "${issue2.validation.includes}"`, typeof issue2.validation.position == "number" && (message = `${message} at one or more positions greater than or equal to ${issue2.validation.position}`)) : "startsWith" in issue2.validation ? message = `Invalid input: must start with "${issue2.validation.startsWith}"` : "endsWith" in issue2.validation ? message = `Invalid input: must end with "${issue2.validation.endsWith}"` : util.assertNever(issue2.validation) : issue2.validation !== "regex" ? message = `Invalid ${issue2.validation}` : message = "Invalid";
      break;
    case ZodIssueCode.too_small:
      issue2.type === "array" ? message = `Array must contain ${issue2.exact ? "exactly" : issue2.inclusive ? "at least" : "more than"} ${issue2.minimum} element(s)` : issue2.type === "string" ? message = `String must contain ${issue2.exact ? "exactly" : issue2.inclusive ? "at least" : "over"} ${issue2.minimum} character(s)` : issue2.type === "number" ? message = `Number must be ${issue2.exact ? "exactly equal to " : issue2.inclusive ? "greater than or equal to " : "greater than "}${issue2.minimum}` : issue2.type === "bigint" ? message = `Number must be ${issue2.exact ? "exactly equal to " : issue2.inclusive ? "greater than or equal to " : "greater than "}${issue2.minimum}` : issue2.type === "date" ? message = `Date must be ${issue2.exact ? "exactly equal to " : issue2.inclusive ? "greater than or equal to " : "greater than "}${new Date(Number(issue2.minimum))}` : message = "Invalid input";
      break;
    case ZodIssueCode.too_big:
      issue2.type === "array" ? message = `Array must contain ${issue2.exact ? "exactly" : issue2.inclusive ? "at most" : "less than"} ${issue2.maximum} element(s)` : issue2.type === "string" ? message = `String must contain ${issue2.exact ? "exactly" : issue2.inclusive ? "at most" : "under"} ${issue2.maximum} character(s)` : issue2.type === "number" ? message = `Number must be ${issue2.exact ? "exactly" : issue2.inclusive ? "less than or equal to" : "less than"} ${issue2.maximum}` : issue2.type === "bigint" ? message = `BigInt must be ${issue2.exact ? "exactly" : issue2.inclusive ? "less than or equal to" : "less than"} ${issue2.maximum}` : issue2.type === "date" ? message = `Date must be ${issue2.exact ? "exactly" : issue2.inclusive ? "smaller than or equal to" : "smaller than"} ${new Date(Number(issue2.maximum))}` : message = "Invalid input";
      break;
    case ZodIssueCode.custom:
      message = "Invalid input";
      break;
    case ZodIssueCode.invalid_intersection_types:
      message = "Intersection results could not be merged";
      break;
    case ZodIssueCode.not_multiple_of:
      message = `Number must be a multiple of ${issue2.multipleOf}`;
      break;
    case ZodIssueCode.not_finite:
      message = "Number must be finite";
      break;
    default:
      message = _ctx.defaultError, util.assertNever(issue2);
  }
  return { message };
}, en_default = errorMap;

// node_modules/zod/v3/errors.js
var overrideErrorMap = en_default;
function setErrorMap(map) {
  overrideErrorMap = map;
}
function getErrorMap() {
  return overrideErrorMap;
}

// node_modules/zod/v3/helpers/parseUtil.js
var makeIssue = (params) => {
  let { data, path: path3, errorMaps, issueData } = params, fullPath = [...path3, ...issueData.path || []], fullIssue = {
    ...issueData,
    path: fullPath
  };
  if (issueData.message !== void 0)
    return {
      ...issueData,
      path: fullPath,
      message: issueData.message
    };
  let errorMessage = "", maps = errorMaps.filter((m) => !!m).slice().reverse();
  for (let map of maps)
    errorMessage = map(fullIssue, { data, defaultError: errorMessage }).message;
  return {
    ...issueData,
    path: fullPath,
    message: errorMessage
  };
}, EMPTY_PATH = [];
function addIssueToContext(ctx, issueData) {
  let overrideMap = getErrorMap(), issue2 = makeIssue({
    issueData,
    data: ctx.data,
    path: ctx.path,
    errorMaps: [
      ctx.common.contextualErrorMap,
      // contextual error map is first priority
      ctx.schemaErrorMap,
      // then schema-bound map if available
      overrideMap,
      // then global override map
      overrideMap === en_default ? void 0 : en_default
      // then global default map
    ].filter((x) => !!x)
  });
  ctx.common.issues.push(issue2);
}
var ParseStatus = class _ParseStatus {
  constructor() {
    this.value = "valid";
  }
  dirty() {
    this.value === "valid" && (this.value = "dirty");
  }
  abort() {
    this.value !== "aborted" && (this.value = "aborted");
  }
  static mergeArray(status, results) {
    let arrayValue = [];
    for (let s of results) {
      if (s.status === "aborted")
        return INVALID;
      s.status === "dirty" && status.dirty(), arrayValue.push(s.value);
    }
    return { status: status.value, value: arrayValue };
  }
  static async mergeObjectAsync(status, pairs) {
    let syncPairs = [];
    for (let pair of pairs) {
      let key = await pair.key, value = await pair.value;
      syncPairs.push({
        key,
        value
      });
    }
    return _ParseStatus.mergeObjectSync(status, syncPairs);
  }
  static mergeObjectSync(status, pairs) {
    let finalObject = {};
    for (let pair of pairs) {
      let { key, value } = pair;
      if (key.status === "aborted" || value.status === "aborted")
        return INVALID;
      key.status === "dirty" && status.dirty(), value.status === "dirty" && status.dirty(), key.value !== "__proto__" && (typeof value.value < "u" || pair.alwaysSet) && (finalObject[key.value] = value.value);
    }
    return { status: status.value, value: finalObject };
  }
}, INVALID = Object.freeze({
  status: "aborted"
}), DIRTY = (value) => ({ status: "dirty", value }), OK = (value) => ({ status: "valid", value }), isAborted = (x) => x.status === "aborted", isDirty = (x) => x.status === "dirty", isValid = (x) => x.status === "valid", isAsync = (x) => typeof Promise < "u" && x instanceof Promise;

// node_modules/zod/v3/helpers/errorUtil.js
var errorUtil;
(function(errorUtil2) {
  errorUtil2.errToObj = (message) => typeof message == "string" ? { message } : message || {}, errorUtil2.toString = (message) => typeof message == "string" ? message : message?.message;
})(errorUtil || (errorUtil = {}));

// node_modules/zod/v3/types.js
var ParseInputLazyPath = class {
  constructor(parent, value, path3, key) {
    this._cachedPath = [], this.parent = parent, this.data = value, this._path = path3, this._key = key;
  }
  get path() {
    return this._cachedPath.length || (Array.isArray(this._key) ? this._cachedPath.push(...this._path, ...this._key) : this._cachedPath.push(...this._path, this._key)), this._cachedPath;
  }
}, handleResult = (ctx, result) => {
  if (isValid(result))
    return { success: !0, data: result.value };
  if (!ctx.common.issues.length)
    throw new Error("Validation failed but no issues detected.");
  return {
    success: !1,
    get error() {
      if (this._error)
        return this._error;
      let error2 = new ZodError(ctx.common.issues);
      return this._error = error2, this._error;
    }
  };
};
function processCreateParams(params) {
  if (!params)
    return {};
  let { errorMap: errorMap2, invalid_type_error, required_error, description } = params;
  if (errorMap2 && (invalid_type_error || required_error))
    throw new Error(`Can't use "invalid_type_error" or "required_error" in conjunction with custom error map.`);
  return errorMap2 ? { errorMap: errorMap2, description } : { errorMap: (iss, ctx) => {
    let { message } = params;
    return iss.code === "invalid_enum_value" ? { message: message ?? ctx.defaultError } : typeof ctx.data > "u" ? { message: message ?? required_error ?? ctx.defaultError } : iss.code !== "invalid_type" ? { message: ctx.defaultError } : { message: message ?? invalid_type_error ?? ctx.defaultError };
  }, description };
}
var ZodType = class {
  get description() {
    return this._def.description;
  }
  _getType(input) {
    return getParsedType(input.data);
  }
  _getOrReturnCtx(input, ctx) {
    return ctx || {
      common: input.parent.common,
      data: input.data,
      parsedType: getParsedType(input.data),
      schemaErrorMap: this._def.errorMap,
      path: input.path,
      parent: input.parent
    };
  }
  _processInputParams(input) {
    return {
      status: new ParseStatus(),
      ctx: {
        common: input.parent.common,
        data: input.data,
        parsedType: getParsedType(input.data),
        schemaErrorMap: this._def.errorMap,
        path: input.path,
        parent: input.parent
      }
    };
  }
  _parseSync(input) {
    let result = this._parse(input);
    if (isAsync(result))
      throw new Error("Synchronous parse encountered promise.");
    return result;
  }
  _parseAsync(input) {
    let result = this._parse(input);
    return Promise.resolve(result);
  }
  parse(data, params) {
    let result = this.safeParse(data, params);
    if (result.success)
      return result.data;
    throw result.error;
  }
  safeParse(data, params) {
    let ctx = {
      common: {
        issues: [],
        async: params?.async ?? !1,
        contextualErrorMap: params?.errorMap
      },
      path: params?.path || [],
      schemaErrorMap: this._def.errorMap,
      parent: null,
      data,
      parsedType: getParsedType(data)
    }, result = this._parseSync({ data, path: ctx.path, parent: ctx });
    return handleResult(ctx, result);
  }
  "~validate"(data) {
    let ctx = {
      common: {
        issues: [],
        async: !!this["~standard"].async
      },
      path: [],
      schemaErrorMap: this._def.errorMap,
      parent: null,
      data,
      parsedType: getParsedType(data)
    };
    if (!this["~standard"].async)
      try {
        let result = this._parseSync({ data, path: [], parent: ctx });
        return isValid(result) ? {
          value: result.value
        } : {
          issues: ctx.common.issues
        };
      } catch (err) {
        err?.message?.toLowerCase()?.includes("encountered") && (this["~standard"].async = !0), ctx.common = {
          issues: [],
          async: !0
        };
      }
    return this._parseAsync({ data, path: [], parent: ctx }).then((result) => isValid(result) ? {
      value: result.value
    } : {
      issues: ctx.common.issues
    });
  }
  async parseAsync(data, params) {
    let result = await this.safeParseAsync(data, params);
    if (result.success)
      return result.data;
    throw result.error;
  }
  async safeParseAsync(data, params) {
    let ctx = {
      common: {
        issues: [],
        contextualErrorMap: params?.errorMap,
        async: !0
      },
      path: params?.path || [],
      schemaErrorMap: this._def.errorMap,
      parent: null,
      data,
      parsedType: getParsedType(data)
    }, maybeAsyncResult = this._parse({ data, path: ctx.path, parent: ctx }), result = await (isAsync(maybeAsyncResult) ? maybeAsyncResult : Promise.resolve(maybeAsyncResult));
    return handleResult(ctx, result);
  }
  refine(check2, message) {
    let getIssueProperties = (val) => typeof message == "string" || typeof message > "u" ? { message } : typeof message == "function" ? message(val) : message;
    return this._refinement((val, ctx) => {
      let result = check2(val), setError = () => ctx.addIssue({
        code: ZodIssueCode.custom,
        ...getIssueProperties(val)
      });
      return typeof Promise < "u" && result instanceof Promise ? result.then((data) => data ? !0 : (setError(), !1)) : result ? !0 : (setError(), !1);
    });
  }
  refinement(check2, refinementData) {
    return this._refinement((val, ctx) => check2(val) ? !0 : (ctx.addIssue(typeof refinementData == "function" ? refinementData(val, ctx) : refinementData), !1));
  }
  _refinement(refinement) {
    return new ZodEffects({
      schema: this,
      typeName: ZodFirstPartyTypeKind.ZodEffects,
      effect: { type: "refinement", refinement }
    });
  }
  superRefine(refinement) {
    return this._refinement(refinement);
  }
  constructor(def) {
    this.spa = this.safeParseAsync, this._def = def, this.parse = this.parse.bind(this), this.safeParse = this.safeParse.bind(this), this.parseAsync = this.parseAsync.bind(this), this.safeParseAsync = this.safeParseAsync.bind(this), this.spa = this.spa.bind(this), this.refine = this.refine.bind(this), this.refinement = this.refinement.bind(this), this.superRefine = this.superRefine.bind(this), this.optional = this.optional.bind(this), this.nullable = this.nullable.bind(this), this.nullish = this.nullish.bind(this), this.array = this.array.bind(this), this.promise = this.promise.bind(this), this.or = this.or.bind(this), this.and = this.and.bind(this), this.transform = this.transform.bind(this), this.brand = this.brand.bind(this), this.default = this.default.bind(this), this.catch = this.catch.bind(this), this.describe = this.describe.bind(this), this.pipe = this.pipe.bind(this), this.readonly = this.readonly.bind(this), this.isNullable = this.isNullable.bind(this), this.isOptional = this.isOptional.bind(this), this["~standard"] = {
      version: 1,
      vendor: "zod",
      validate: (data) => this["~validate"](data)
    };
  }
  optional() {
    return ZodOptional.create(this, this._def);
  }
  nullable() {
    return ZodNullable.create(this, this._def);
  }
  nullish() {
    return this.nullable().optional();
  }
  array() {
    return ZodArray.create(this);
  }
  promise() {
    return ZodPromise.create(this, this._def);
  }
  or(option) {
    return ZodUnion.create([this, option], this._def);
  }
  and(incoming) {
    return ZodIntersection.create(this, incoming, this._def);
  }
  transform(transform2) {
    return new ZodEffects({
      ...processCreateParams(this._def),
      schema: this,
      typeName: ZodFirstPartyTypeKind.ZodEffects,
      effect: { type: "transform", transform: transform2 }
    });
  }
  default(def) {
    let defaultValueFunc = typeof def == "function" ? def : () => def;
    return new ZodDefault({
      ...processCreateParams(this._def),
      innerType: this,
      defaultValue: defaultValueFunc,
      typeName: ZodFirstPartyTypeKind.ZodDefault
    });
  }
  brand() {
    return new ZodBranded({
      typeName: ZodFirstPartyTypeKind.ZodBranded,
      type: this,
      ...processCreateParams(this._def)
    });
  }
  catch(def) {
    let catchValueFunc = typeof def == "function" ? def : () => def;
    return new ZodCatch({
      ...processCreateParams(this._def),
      innerType: this,
      catchValue: catchValueFunc,
      typeName: ZodFirstPartyTypeKind.ZodCatch
    });
  }
  describe(description) {
    let This = this.constructor;
    return new This({
      ...this._def,
      description
    });
  }
  pipe(target) {
    return ZodPipeline.create(this, target);
  }
  readonly() {
    return ZodReadonly.create(this);
  }
  isOptional() {
    return this.safeParse(void 0).success;
  }
  isNullable() {
    return this.safeParse(null).success;
  }
}, cuidRegex = /^c[^\s-]{8,}$/i, cuid2Regex = /^[0-9a-z]+$/, ulidRegex = /^[0-9A-HJKMNP-TV-Z]{26}$/i, uuidRegex = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/i, nanoidRegex = /^[a-z0-9_-]{21}$/i, jwtRegex = /^[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]*$/, durationRegex = /^[-+]?P(?!$)(?:(?:[-+]?\d+Y)|(?:[-+]?\d+[.,]\d+Y$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:(?:[-+]?\d+W)|(?:[-+]?\d+[.,]\d+W$))?(?:(?:[-+]?\d+D)|(?:[-+]?\d+[.,]\d+D$))?(?:T(?=[\d+-])(?:(?:[-+]?\d+H)|(?:[-+]?\d+[.,]\d+H$))?(?:(?:[-+]?\d+M)|(?:[-+]?\d+[.,]\d+M$))?(?:[-+]?\d+(?:[.,]\d+)?S)?)??$/, emailRegex = /^(?!\.)(?!.*\.\.)([A-Z0-9_'+\-\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\-]*\.)+[A-Z]{2,}$/i, _emojiRegex = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$", emojiRegex, ipv4Regex = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/, ipv4CidrRegex = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\/(3[0-2]|[12]?[0-9])$/, ipv6Regex = /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$/, ipv6CidrRegex = /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))\/(12[0-8]|1[01][0-9]|[1-9]?[0-9])$/, base64Regex = /^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$/, base64urlRegex = /^([0-9a-zA-Z-_]{4})*(([0-9a-zA-Z-_]{2}(==)?)|([0-9a-zA-Z-_]{3}(=)?))?$/, dateRegexSource = "((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))", dateRegex = new RegExp(`^${dateRegexSource}$`);
function timeRegexSource(args) {
  let secondsRegexSource = "[0-5]\\d";
  args.precision ? secondsRegexSource = `${secondsRegexSource}\\.\\d{${args.precision}}` : args.precision == null && (secondsRegexSource = `${secondsRegexSource}(\\.\\d+)?`);
  let secondsQuantifier = args.precision ? "+" : "?";
  return `([01]\\d|2[0-3]):[0-5]\\d(:${secondsRegexSource})${secondsQuantifier}`;
}
function timeRegex(args) {
  return new RegExp(`^${timeRegexSource(args)}$`);
}
function datetimeRegex(args) {
  let regex = `${dateRegexSource}T${timeRegexSource(args)}`, opts = [];
  return opts.push(args.local ? "Z?" : "Z"), args.offset && opts.push("([+-]\\d{2}:?\\d{2})"), regex = `${regex}(${opts.join("|")})`, new RegExp(`^${regex}$`);
}
function isValidIP(ip, version2) {
  return !!((version2 === "v4" || !version2) && ipv4Regex.test(ip) || (version2 === "v6" || !version2) && ipv6Regex.test(ip));
}
function isValidJWT(jwt, alg) {
  if (!jwtRegex.test(jwt))
    return !1;
  try {
    let [header] = jwt.split(".");
    if (!header)
      return !1;
    let base642 = header.replace(/-/g, "+").replace(/_/g, "/").padEnd(header.length + (4 - header.length % 4) % 4, "="), decoded = JSON.parse(atob(base642));
    return !(typeof decoded != "object" || decoded === null || "typ" in decoded && decoded?.typ !== "JWT" || !decoded.alg || alg && decoded.alg !== alg);
  } catch {
    return !1;
  }
}
function isValidCidr(ip, version2) {
  return !!((version2 === "v4" || !version2) && ipv4CidrRegex.test(ip) || (version2 === "v6" || !version2) && ipv6CidrRegex.test(ip));
}
var ZodString = class _ZodString2 extends ZodType {
  _parse(input) {
    if (this._def.coerce && (input.data = String(input.data)), this._getType(input) !== ZodParsedType.string) {
      let ctx2 = this._getOrReturnCtx(input);
      return addIssueToContext(ctx2, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.string,
        received: ctx2.parsedType
      }), INVALID;
    }
    let status = new ParseStatus(), ctx;
    for (let check2 of this._def.checks)
      if (check2.kind === "min")
        input.data.length < check2.value && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          code: ZodIssueCode.too_small,
          minimum: check2.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "max")
        input.data.length > check2.value && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          code: ZodIssueCode.too_big,
          maximum: check2.value,
          type: "string",
          inclusive: !0,
          exact: !1,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "length") {
        let tooBig = input.data.length > check2.value, tooSmall = input.data.length < check2.value;
        (tooBig || tooSmall) && (ctx = this._getOrReturnCtx(input, ctx), tooBig ? addIssueToContext(ctx, {
          code: ZodIssueCode.too_big,
          maximum: check2.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: check2.message
        }) : tooSmall && addIssueToContext(ctx, {
          code: ZodIssueCode.too_small,
          minimum: check2.value,
          type: "string",
          inclusive: !0,
          exact: !0,
          message: check2.message
        }), status.dirty());
      } else if (check2.kind === "email")
        emailRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          validation: "email",
          code: ZodIssueCode.invalid_string,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "emoji")
        emojiRegex || (emojiRegex = new RegExp(_emojiRegex, "u")), emojiRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          validation: "emoji",
          code: ZodIssueCode.invalid_string,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "uuid")
        uuidRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          validation: "uuid",
          code: ZodIssueCode.invalid_string,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "nanoid")
        nanoidRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          validation: "nanoid",
          code: ZodIssueCode.invalid_string,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "cuid")
        cuidRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          validation: "cuid",
          code: ZodIssueCode.invalid_string,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "cuid2")
        cuid2Regex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          validation: "cuid2",
          code: ZodIssueCode.invalid_string,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "ulid")
        ulidRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
          validation: "ulid",
          code: ZodIssueCode.invalid_string,
          message: check2.message
        }), status.dirty());
      else if (check2.kind === "url")
        try {
          new URL(input.data);
        } catch {
          ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
            validation: "url",
            code: ZodIssueCode.invalid_string,
            message: check2.message
          }), status.dirty();
        }
      else check2.kind === "regex" ? (check2.regex.lastIndex = 0, check2.regex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        validation: "regex",
        code: ZodIssueCode.invalid_string,
        message: check2.message
      }), status.dirty())) : check2.kind === "trim" ? input.data = input.data.trim() : check2.kind === "includes" ? input.data.includes(check2.value, check2.position) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_string,
        validation: { includes: check2.value, position: check2.position },
        message: check2.message
      }), status.dirty()) : check2.kind === "toLowerCase" ? input.data = input.data.toLowerCase() : check2.kind === "toUpperCase" ? input.data = input.data.toUpperCase() : check2.kind === "startsWith" ? input.data.startsWith(check2.value) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_string,
        validation: { startsWith: check2.value },
        message: check2.message
      }), status.dirty()) : check2.kind === "endsWith" ? input.data.endsWith(check2.value) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_string,
        validation: { endsWith: check2.value },
        message: check2.message
      }), status.dirty()) : check2.kind === "datetime" ? datetimeRegex(check2).test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_string,
        validation: "datetime",
        message: check2.message
      }), status.dirty()) : check2.kind === "date" ? dateRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_string,
        validation: "date",
        message: check2.message
      }), status.dirty()) : check2.kind === "time" ? timeRegex(check2).test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_string,
        validation: "time",
        message: check2.message
      }), status.dirty()) : check2.kind === "duration" ? durationRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        validation: "duration",
        code: ZodIssueCode.invalid_string,
        message: check2.message
      }), status.dirty()) : check2.kind === "ip" ? isValidIP(input.data, check2.version) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        validation: "ip",
        code: ZodIssueCode.invalid_string,
        message: check2.message
      }), status.dirty()) : check2.kind === "jwt" ? isValidJWT(input.data, check2.alg) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        validation: "jwt",
        code: ZodIssueCode.invalid_string,
        message: check2.message
      }), status.dirty()) : check2.kind === "cidr" ? isValidCidr(input.data, check2.version) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        validation: "cidr",
        code: ZodIssueCode.invalid_string,
        message: check2.message
      }), status.dirty()) : check2.kind === "base64" ? base64Regex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        validation: "base64",
        code: ZodIssueCode.invalid_string,
        message: check2.message
      }), status.dirty()) : check2.kind === "base64url" ? base64urlRegex.test(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        validation: "base64url",
        code: ZodIssueCode.invalid_string,
        message: check2.message
      }), status.dirty()) : util.assertNever(check2);
    return { status: status.value, value: input.data };
  }
  _regex(regex, validation, message) {
    return this.refinement((data) => regex.test(data), {
      validation,
      code: ZodIssueCode.invalid_string,
      ...errorUtil.errToObj(message)
    });
  }
  _addCheck(check2) {
    return new _ZodString2({
      ...this._def,
      checks: [...this._def.checks, check2]
    });
  }
  email(message) {
    return this._addCheck({ kind: "email", ...errorUtil.errToObj(message) });
  }
  url(message) {
    return this._addCheck({ kind: "url", ...errorUtil.errToObj(message) });
  }
  emoji(message) {
    return this._addCheck({ kind: "emoji", ...errorUtil.errToObj(message) });
  }
  uuid(message) {
    return this._addCheck({ kind: "uuid", ...errorUtil.errToObj(message) });
  }
  nanoid(message) {
    return this._addCheck({ kind: "nanoid", ...errorUtil.errToObj(message) });
  }
  cuid(message) {
    return this._addCheck({ kind: "cuid", ...errorUtil.errToObj(message) });
  }
  cuid2(message) {
    return this._addCheck({ kind: "cuid2", ...errorUtil.errToObj(message) });
  }
  ulid(message) {
    return this._addCheck({ kind: "ulid", ...errorUtil.errToObj(message) });
  }
  base64(message) {
    return this._addCheck({ kind: "base64", ...errorUtil.errToObj(message) });
  }
  base64url(message) {
    return this._addCheck({
      kind: "base64url",
      ...errorUtil.errToObj(message)
    });
  }
  jwt(options) {
    return this._addCheck({ kind: "jwt", ...errorUtil.errToObj(options) });
  }
  ip(options) {
    return this._addCheck({ kind: "ip", ...errorUtil.errToObj(options) });
  }
  cidr(options) {
    return this._addCheck({ kind: "cidr", ...errorUtil.errToObj(options) });
  }
  datetime(options) {
    return typeof options == "string" ? this._addCheck({
      kind: "datetime",
      precision: null,
      offset: !1,
      local: !1,
      message: options
    }) : this._addCheck({
      kind: "datetime",
      precision: typeof options?.precision > "u" ? null : options?.precision,
      offset: options?.offset ?? !1,
      local: options?.local ?? !1,
      ...errorUtil.errToObj(options?.message)
    });
  }
  date(message) {
    return this._addCheck({ kind: "date", message });
  }
  time(options) {
    return typeof options == "string" ? this._addCheck({
      kind: "time",
      precision: null,
      message: options
    }) : this._addCheck({
      kind: "time",
      precision: typeof options?.precision > "u" ? null : options?.precision,
      ...errorUtil.errToObj(options?.message)
    });
  }
  duration(message) {
    return this._addCheck({ kind: "duration", ...errorUtil.errToObj(message) });
  }
  regex(regex, message) {
    return this._addCheck({
      kind: "regex",
      regex,
      ...errorUtil.errToObj(message)
    });
  }
  includes(value, options) {
    return this._addCheck({
      kind: "includes",
      value,
      position: options?.position,
      ...errorUtil.errToObj(options?.message)
    });
  }
  startsWith(value, message) {
    return this._addCheck({
      kind: "startsWith",
      value,
      ...errorUtil.errToObj(message)
    });
  }
  endsWith(value, message) {
    return this._addCheck({
      kind: "endsWith",
      value,
      ...errorUtil.errToObj(message)
    });
  }
  min(minLength, message) {
    return this._addCheck({
      kind: "min",
      value: minLength,
      ...errorUtil.errToObj(message)
    });
  }
  max(maxLength, message) {
    return this._addCheck({
      kind: "max",
      value: maxLength,
      ...errorUtil.errToObj(message)
    });
  }
  length(len, message) {
    return this._addCheck({
      kind: "length",
      value: len,
      ...errorUtil.errToObj(message)
    });
  }
  /**
   * Equivalent to `.min(1)`
   */
  nonempty(message) {
    return this.min(1, errorUtil.errToObj(message));
  }
  trim() {
    return new _ZodString2({
      ...this._def,
      checks: [...this._def.checks, { kind: "trim" }]
    });
  }
  toLowerCase() {
    return new _ZodString2({
      ...this._def,
      checks: [...this._def.checks, { kind: "toLowerCase" }]
    });
  }
  toUpperCase() {
    return new _ZodString2({
      ...this._def,
      checks: [...this._def.checks, { kind: "toUpperCase" }]
    });
  }
  get isDatetime() {
    return !!this._def.checks.find((ch) => ch.kind === "datetime");
  }
  get isDate() {
    return !!this._def.checks.find((ch) => ch.kind === "date");
  }
  get isTime() {
    return !!this._def.checks.find((ch) => ch.kind === "time");
  }
  get isDuration() {
    return !!this._def.checks.find((ch) => ch.kind === "duration");
  }
  get isEmail() {
    return !!this._def.checks.find((ch) => ch.kind === "email");
  }
  get isURL() {
    return !!this._def.checks.find((ch) => ch.kind === "url");
  }
  get isEmoji() {
    return !!this._def.checks.find((ch) => ch.kind === "emoji");
  }
  get isUUID() {
    return !!this._def.checks.find((ch) => ch.kind === "uuid");
  }
  get isNANOID() {
    return !!this._def.checks.find((ch) => ch.kind === "nanoid");
  }
  get isCUID() {
    return !!this._def.checks.find((ch) => ch.kind === "cuid");
  }
  get isCUID2() {
    return !!this._def.checks.find((ch) => ch.kind === "cuid2");
  }
  get isULID() {
    return !!this._def.checks.find((ch) => ch.kind === "ulid");
  }
  get isIP() {
    return !!this._def.checks.find((ch) => ch.kind === "ip");
  }
  get isCIDR() {
    return !!this._def.checks.find((ch) => ch.kind === "cidr");
  }
  get isBase64() {
    return !!this._def.checks.find((ch) => ch.kind === "base64");
  }
  get isBase64url() {
    return !!this._def.checks.find((ch) => ch.kind === "base64url");
  }
  get minLength() {
    let min = null;
    for (let ch of this._def.checks)
      ch.kind === "min" && (min === null || ch.value > min) && (min = ch.value);
    return min;
  }
  get maxLength() {
    let max = null;
    for (let ch of this._def.checks)
      ch.kind === "max" && (max === null || ch.value < max) && (max = ch.value);
    return max;
  }
};
ZodString.create = (params) => new ZodString({
  checks: [],
  typeName: ZodFirstPartyTypeKind.ZodString,
  coerce: params?.coerce ?? !1,
  ...processCreateParams(params)
});
function floatSafeRemainder(val, step) {
  let valDecCount = (val.toString().split(".")[1] || "").length, stepDecCount = (step.toString().split(".")[1] || "").length, decCount = valDecCount > stepDecCount ? valDecCount : stepDecCount, valInt = Number.parseInt(val.toFixed(decCount).replace(".", "")), stepInt = Number.parseInt(step.toFixed(decCount).replace(".", ""));
  return valInt % stepInt / 10 ** decCount;
}
var ZodNumber = class _ZodNumber extends ZodType {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte, this.step = this.multipleOf;
  }
  _parse(input) {
    if (this._def.coerce && (input.data = Number(input.data)), this._getType(input) !== ZodParsedType.number) {
      let ctx2 = this._getOrReturnCtx(input);
      return addIssueToContext(ctx2, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.number,
        received: ctx2.parsedType
      }), INVALID;
    }
    let ctx, status = new ParseStatus();
    for (let check2 of this._def.checks)
      check2.kind === "int" ? util.isInteger(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: "integer",
        received: "float",
        message: check2.message
      }), status.dirty()) : check2.kind === "min" ? (check2.inclusive ? input.data < check2.value : input.data <= check2.value) && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.too_small,
        minimum: check2.value,
        type: "number",
        inclusive: check2.inclusive,
        exact: !1,
        message: check2.message
      }), status.dirty()) : check2.kind === "max" ? (check2.inclusive ? input.data > check2.value : input.data >= check2.value) && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.too_big,
        maximum: check2.value,
        type: "number",
        inclusive: check2.inclusive,
        exact: !1,
        message: check2.message
      }), status.dirty()) : check2.kind === "multipleOf" ? floatSafeRemainder(input.data, check2.value) !== 0 && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.not_multiple_of,
        multipleOf: check2.value,
        message: check2.message
      }), status.dirty()) : check2.kind === "finite" ? Number.isFinite(input.data) || (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.not_finite,
        message: check2.message
      }), status.dirty()) : util.assertNever(check2);
    return { status: status.value, value: input.data };
  }
  gte(value, message) {
    return this.setLimit("min", value, !0, errorUtil.toString(message));
  }
  gt(value, message) {
    return this.setLimit("min", value, !1, errorUtil.toString(message));
  }
  lte(value, message) {
    return this.setLimit("max", value, !0, errorUtil.toString(message));
  }
  lt(value, message) {
    return this.setLimit("max", value, !1, errorUtil.toString(message));
  }
  setLimit(kind, value, inclusive, message) {
    return new _ZodNumber({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind,
          value,
          inclusive,
          message: errorUtil.toString(message)
        }
      ]
    });
  }
  _addCheck(check2) {
    return new _ZodNumber({
      ...this._def,
      checks: [...this._def.checks, check2]
    });
  }
  int(message) {
    return this._addCheck({
      kind: "int",
      message: errorUtil.toString(message)
    });
  }
  positive(message) {
    return this._addCheck({
      kind: "min",
      value: 0,
      inclusive: !1,
      message: errorUtil.toString(message)
    });
  }
  negative(message) {
    return this._addCheck({
      kind: "max",
      value: 0,
      inclusive: !1,
      message: errorUtil.toString(message)
    });
  }
  nonpositive(message) {
    return this._addCheck({
      kind: "max",
      value: 0,
      inclusive: !0,
      message: errorUtil.toString(message)
    });
  }
  nonnegative(message) {
    return this._addCheck({
      kind: "min",
      value: 0,
      inclusive: !0,
      message: errorUtil.toString(message)
    });
  }
  multipleOf(value, message) {
    return this._addCheck({
      kind: "multipleOf",
      value,
      message: errorUtil.toString(message)
    });
  }
  finite(message) {
    return this._addCheck({
      kind: "finite",
      message: errorUtil.toString(message)
    });
  }
  safe(message) {
    return this._addCheck({
      kind: "min",
      inclusive: !0,
      value: Number.MIN_SAFE_INTEGER,
      message: errorUtil.toString(message)
    })._addCheck({
      kind: "max",
      inclusive: !0,
      value: Number.MAX_SAFE_INTEGER,
      message: errorUtil.toString(message)
    });
  }
  get minValue() {
    let min = null;
    for (let ch of this._def.checks)
      ch.kind === "min" && (min === null || ch.value > min) && (min = ch.value);
    return min;
  }
  get maxValue() {
    let max = null;
    for (let ch of this._def.checks)
      ch.kind === "max" && (max === null || ch.value < max) && (max = ch.value);
    return max;
  }
  get isInt() {
    return !!this._def.checks.find((ch) => ch.kind === "int" || ch.kind === "multipleOf" && util.isInteger(ch.value));
  }
  get isFinite() {
    let max = null, min = null;
    for (let ch of this._def.checks) {
      if (ch.kind === "finite" || ch.kind === "int" || ch.kind === "multipleOf")
        return !0;
      ch.kind === "min" ? (min === null || ch.value > min) && (min = ch.value) : ch.kind === "max" && (max === null || ch.value < max) && (max = ch.value);
    }
    return Number.isFinite(min) && Number.isFinite(max);
  }
};
ZodNumber.create = (params) => new ZodNumber({
  checks: [],
  typeName: ZodFirstPartyTypeKind.ZodNumber,
  coerce: params?.coerce || !1,
  ...processCreateParams(params)
});
var ZodBigInt = class _ZodBigInt extends ZodType {
  constructor() {
    super(...arguments), this.min = this.gte, this.max = this.lte;
  }
  _parse(input) {
    if (this._def.coerce)
      try {
        input.data = BigInt(input.data);
      } catch {
        return this._getInvalidInput(input);
      }
    if (this._getType(input) !== ZodParsedType.bigint)
      return this._getInvalidInput(input);
    let ctx, status = new ParseStatus();
    for (let check2 of this._def.checks)
      check2.kind === "min" ? (check2.inclusive ? input.data < check2.value : input.data <= check2.value) && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.too_small,
        type: "bigint",
        minimum: check2.value,
        inclusive: check2.inclusive,
        message: check2.message
      }), status.dirty()) : check2.kind === "max" ? (check2.inclusive ? input.data > check2.value : input.data >= check2.value) && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.too_big,
        type: "bigint",
        maximum: check2.value,
        inclusive: check2.inclusive,
        message: check2.message
      }), status.dirty()) : check2.kind === "multipleOf" ? input.data % check2.value !== BigInt(0) && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.not_multiple_of,
        multipleOf: check2.value,
        message: check2.message
      }), status.dirty()) : util.assertNever(check2);
    return { status: status.value, value: input.data };
  }
  _getInvalidInput(input) {
    let ctx = this._getOrReturnCtx(input);
    return addIssueToContext(ctx, {
      code: ZodIssueCode.invalid_type,
      expected: ZodParsedType.bigint,
      received: ctx.parsedType
    }), INVALID;
  }
  gte(value, message) {
    return this.setLimit("min", value, !0, errorUtil.toString(message));
  }
  gt(value, message) {
    return this.setLimit("min", value, !1, errorUtil.toString(message));
  }
  lte(value, message) {
    return this.setLimit("max", value, !0, errorUtil.toString(message));
  }
  lt(value, message) {
    return this.setLimit("max", value, !1, errorUtil.toString(message));
  }
  setLimit(kind, value, inclusive, message) {
    return new _ZodBigInt({
      ...this._def,
      checks: [
        ...this._def.checks,
        {
          kind,
          value,
          inclusive,
          message: errorUtil.toString(message)
        }
      ]
    });
  }
  _addCheck(check2) {
    return new _ZodBigInt({
      ...this._def,
      checks: [...this._def.checks, check2]
    });
  }
  positive(message) {
    return this._addCheck({
      kind: "min",
      value: BigInt(0),
      inclusive: !1,
      message: errorUtil.toString(message)
    });
  }
  negative(message) {
    return this._addCheck({
      kind: "max",
      value: BigInt(0),
      inclusive: !1,
      message: errorUtil.toString(message)
    });
  }
  nonpositive(message) {
    return this._addCheck({
      kind: "max",
      value: BigInt(0),
      inclusive: !0,
      message: errorUtil.toString(message)
    });
  }
  nonnegative(message) {
    return this._addCheck({
      kind: "min",
      value: BigInt(0),
      inclusive: !0,
      message: errorUtil.toString(message)
    });
  }
  multipleOf(value, message) {
    return this._addCheck({
      kind: "multipleOf",
      value,
      message: errorUtil.toString(message)
    });
  }
  get minValue() {
    let min = null;
    for (let ch of this._def.checks)
      ch.kind === "min" && (min === null || ch.value > min) && (min = ch.value);
    return min;
  }
  get maxValue() {
    let max = null;
    for (let ch of this._def.checks)
      ch.kind === "max" && (max === null || ch.value < max) && (max = ch.value);
    return max;
  }
};
ZodBigInt.create = (params) => new ZodBigInt({
  checks: [],
  typeName: ZodFirstPartyTypeKind.ZodBigInt,
  coerce: params?.coerce ?? !1,
  ...processCreateParams(params)
});
var ZodBoolean = class extends ZodType {
  _parse(input) {
    if (this._def.coerce && (input.data = !!input.data), this._getType(input) !== ZodParsedType.boolean) {
      let ctx = this._getOrReturnCtx(input);
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.boolean,
        received: ctx.parsedType
      }), INVALID;
    }
    return OK(input.data);
  }
};
ZodBoolean.create = (params) => new ZodBoolean({
  typeName: ZodFirstPartyTypeKind.ZodBoolean,
  coerce: params?.coerce || !1,
  ...processCreateParams(params)
});
var ZodDate = class _ZodDate extends ZodType {
  _parse(input) {
    if (this._def.coerce && (input.data = new Date(input.data)), this._getType(input) !== ZodParsedType.date) {
      let ctx2 = this._getOrReturnCtx(input);
      return addIssueToContext(ctx2, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.date,
        received: ctx2.parsedType
      }), INVALID;
    }
    if (Number.isNaN(input.data.getTime())) {
      let ctx2 = this._getOrReturnCtx(input);
      return addIssueToContext(ctx2, {
        code: ZodIssueCode.invalid_date
      }), INVALID;
    }
    let status = new ParseStatus(), ctx;
    for (let check2 of this._def.checks)
      check2.kind === "min" ? input.data.getTime() < check2.value && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.too_small,
        message: check2.message,
        inclusive: !0,
        exact: !1,
        minimum: check2.value,
        type: "date"
      }), status.dirty()) : check2.kind === "max" ? input.data.getTime() > check2.value && (ctx = this._getOrReturnCtx(input, ctx), addIssueToContext(ctx, {
        code: ZodIssueCode.too_big,
        message: check2.message,
        inclusive: !0,
        exact: !1,
        maximum: check2.value,
        type: "date"
      }), status.dirty()) : util.assertNever(check2);
    return {
      status: status.value,
      value: new Date(input.data.getTime())
    };
  }
  _addCheck(check2) {
    return new _ZodDate({
      ...this._def,
      checks: [...this._def.checks, check2]
    });
  }
  min(minDate, message) {
    return this._addCheck({
      kind: "min",
      value: minDate.getTime(),
      message: errorUtil.toString(message)
    });
  }
  max(maxDate, message) {
    return this._addCheck({
      kind: "max",
      value: maxDate.getTime(),
      message: errorUtil.toString(message)
    });
  }
  get minDate() {
    let min = null;
    for (let ch of this._def.checks)
      ch.kind === "min" && (min === null || ch.value > min) && (min = ch.value);
    return min != null ? new Date(min) : null;
  }
  get maxDate() {
    let max = null;
    for (let ch of this._def.checks)
      ch.kind === "max" && (max === null || ch.value < max) && (max = ch.value);
    return max != null ? new Date(max) : null;
  }
};
ZodDate.create = (params) => new ZodDate({
  checks: [],
  coerce: params?.coerce || !1,
  typeName: ZodFirstPartyTypeKind.ZodDate,
  ...processCreateParams(params)
});
var ZodSymbol = class extends ZodType {
  _parse(input) {
    if (this._getType(input) !== ZodParsedType.symbol) {
      let ctx = this._getOrReturnCtx(input);
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.symbol,
        received: ctx.parsedType
      }), INVALID;
    }
    return OK(input.data);
  }
};
ZodSymbol.create = (params) => new ZodSymbol({
  typeName: ZodFirstPartyTypeKind.ZodSymbol,
  ...processCreateParams(params)
});
var ZodUndefined = class extends ZodType {
  _parse(input) {
    if (this._getType(input) !== ZodParsedType.undefined) {
      let ctx = this._getOrReturnCtx(input);
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.undefined,
        received: ctx.parsedType
      }), INVALID;
    }
    return OK(input.data);
  }
};
ZodUndefined.create = (params) => new ZodUndefined({
  typeName: ZodFirstPartyTypeKind.ZodUndefined,
  ...processCreateParams(params)
});
var ZodNull = class extends ZodType {
  _parse(input) {
    if (this._getType(input) !== ZodParsedType.null) {
      let ctx = this._getOrReturnCtx(input);
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.null,
        received: ctx.parsedType
      }), INVALID;
    }
    return OK(input.data);
  }
};
ZodNull.create = (params) => new ZodNull({
  typeName: ZodFirstPartyTypeKind.ZodNull,
  ...processCreateParams(params)
});
var ZodAny = class extends ZodType {
  constructor() {
    super(...arguments), this._any = !0;
  }
  _parse(input) {
    return OK(input.data);
  }
};
ZodAny.create = (params) => new ZodAny({
  typeName: ZodFirstPartyTypeKind.ZodAny,
  ...processCreateParams(params)
});
var ZodUnknown = class extends ZodType {
  constructor() {
    super(...arguments), this._unknown = !0;
  }
  _parse(input) {
    return OK(input.data);
  }
};
ZodUnknown.create = (params) => new ZodUnknown({
  typeName: ZodFirstPartyTypeKind.ZodUnknown,
  ...processCreateParams(params)
});
var ZodNever = class extends ZodType {
  _parse(input) {
    let ctx = this._getOrReturnCtx(input);
    return addIssueToContext(ctx, {
      code: ZodIssueCode.invalid_type,
      expected: ZodParsedType.never,
      received: ctx.parsedType
    }), INVALID;
  }
};
ZodNever.create = (params) => new ZodNever({
  typeName: ZodFirstPartyTypeKind.ZodNever,
  ...processCreateParams(params)
});
var ZodVoid = class extends ZodType {
  _parse(input) {
    if (this._getType(input) !== ZodParsedType.undefined) {
      let ctx = this._getOrReturnCtx(input);
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.void,
        received: ctx.parsedType
      }), INVALID;
    }
    return OK(input.data);
  }
};
ZodVoid.create = (params) => new ZodVoid({
  typeName: ZodFirstPartyTypeKind.ZodVoid,
  ...processCreateParams(params)
});
var ZodArray = class _ZodArray extends ZodType {
  _parse(input) {
    let { ctx, status } = this._processInputParams(input), def = this._def;
    if (ctx.parsedType !== ZodParsedType.array)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.array,
        received: ctx.parsedType
      }), INVALID;
    if (def.exactLength !== null) {
      let tooBig = ctx.data.length > def.exactLength.value, tooSmall = ctx.data.length < def.exactLength.value;
      (tooBig || tooSmall) && (addIssueToContext(ctx, {
        code: tooBig ? ZodIssueCode.too_big : ZodIssueCode.too_small,
        minimum: tooSmall ? def.exactLength.value : void 0,
        maximum: tooBig ? def.exactLength.value : void 0,
        type: "array",
        inclusive: !0,
        exact: !0,
        message: def.exactLength.message
      }), status.dirty());
    }
    if (def.minLength !== null && ctx.data.length < def.minLength.value && (addIssueToContext(ctx, {
      code: ZodIssueCode.too_small,
      minimum: def.minLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: def.minLength.message
    }), status.dirty()), def.maxLength !== null && ctx.data.length > def.maxLength.value && (addIssueToContext(ctx, {
      code: ZodIssueCode.too_big,
      maximum: def.maxLength.value,
      type: "array",
      inclusive: !0,
      exact: !1,
      message: def.maxLength.message
    }), status.dirty()), ctx.common.async)
      return Promise.all([...ctx.data].map((item, i) => def.type._parseAsync(new ParseInputLazyPath(ctx, item, ctx.path, i)))).then((result2) => ParseStatus.mergeArray(status, result2));
    let result = [...ctx.data].map((item, i) => def.type._parseSync(new ParseInputLazyPath(ctx, item, ctx.path, i)));
    return ParseStatus.mergeArray(status, result);
  }
  get element() {
    return this._def.type;
  }
  min(minLength, message) {
    return new _ZodArray({
      ...this._def,
      minLength: { value: minLength, message: errorUtil.toString(message) }
    });
  }
  max(maxLength, message) {
    return new _ZodArray({
      ...this._def,
      maxLength: { value: maxLength, message: errorUtil.toString(message) }
    });
  }
  length(len, message) {
    return new _ZodArray({
      ...this._def,
      exactLength: { value: len, message: errorUtil.toString(message) }
    });
  }
  nonempty(message) {
    return this.min(1, message);
  }
};
ZodArray.create = (schema, params) => new ZodArray({
  type: schema,
  minLength: null,
  maxLength: null,
  exactLength: null,
  typeName: ZodFirstPartyTypeKind.ZodArray,
  ...processCreateParams(params)
});
function deepPartialify(schema) {
  if (schema instanceof ZodObject) {
    let newShape = {};
    for (let key in schema.shape) {
      let fieldSchema = schema.shape[key];
      newShape[key] = ZodOptional.create(deepPartialify(fieldSchema));
    }
    return new ZodObject({
      ...schema._def,
      shape: () => newShape
    });
  } else return schema instanceof ZodArray ? new ZodArray({
    ...schema._def,
    type: deepPartialify(schema.element)
  }) : schema instanceof ZodOptional ? ZodOptional.create(deepPartialify(schema.unwrap())) : schema instanceof ZodNullable ? ZodNullable.create(deepPartialify(schema.unwrap())) : schema instanceof ZodTuple ? ZodTuple.create(schema.items.map((item) => deepPartialify(item))) : schema;
}
var ZodObject = class _ZodObject extends ZodType {
  constructor() {
    super(...arguments), this._cached = null, this.nonstrict = this.passthrough, this.augment = this.extend;
  }
  _getCached() {
    if (this._cached !== null)
      return this._cached;
    let shape = this._def.shape(), keys = util.objectKeys(shape);
    return this._cached = { shape, keys }, this._cached;
  }
  _parse(input) {
    if (this._getType(input) !== ZodParsedType.object) {
      let ctx2 = this._getOrReturnCtx(input);
      return addIssueToContext(ctx2, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.object,
        received: ctx2.parsedType
      }), INVALID;
    }
    let { status, ctx } = this._processInputParams(input), { shape, keys: shapeKeys } = this._getCached(), extraKeys = [];
    if (!(this._def.catchall instanceof ZodNever && this._def.unknownKeys === "strip"))
      for (let key in ctx.data)
        shapeKeys.includes(key) || extraKeys.push(key);
    let pairs = [];
    for (let key of shapeKeys) {
      let keyValidator = shape[key], value = ctx.data[key];
      pairs.push({
        key: { status: "valid", value: key },
        value: keyValidator._parse(new ParseInputLazyPath(ctx, value, ctx.path, key)),
        alwaysSet: key in ctx.data
      });
    }
    if (this._def.catchall instanceof ZodNever) {
      let unknownKeys = this._def.unknownKeys;
      if (unknownKeys === "passthrough")
        for (let key of extraKeys)
          pairs.push({
            key: { status: "valid", value: key },
            value: { status: "valid", value: ctx.data[key] }
          });
      else if (unknownKeys === "strict")
        extraKeys.length > 0 && (addIssueToContext(ctx, {
          code: ZodIssueCode.unrecognized_keys,
          keys: extraKeys
        }), status.dirty());
      else if (unknownKeys !== "strip")
        throw new Error("Internal ZodObject error: invalid unknownKeys value.");
    } else {
      let catchall = this._def.catchall;
      for (let key of extraKeys) {
        let value = ctx.data[key];
        pairs.push({
          key: { status: "valid", value: key },
          value: catchall._parse(
            new ParseInputLazyPath(ctx, value, ctx.path, key)
            //, ctx.child(key), value, getParsedType(value)
          ),
          alwaysSet: key in ctx.data
        });
      }
    }
    return ctx.common.async ? Promise.resolve().then(async () => {
      let syncPairs = [];
      for (let pair of pairs) {
        let key = await pair.key, value = await pair.value;
        syncPairs.push({
          key,
          value,
          alwaysSet: pair.alwaysSet
        });
      }
      return syncPairs;
    }).then((syncPairs) => ParseStatus.mergeObjectSync(status, syncPairs)) : ParseStatus.mergeObjectSync(status, pairs);
  }
  get shape() {
    return this._def.shape();
  }
  strict(message) {
    return errorUtil.errToObj, new _ZodObject({
      ...this._def,
      unknownKeys: "strict",
      ...message !== void 0 ? {
        errorMap: (issue2, ctx) => {
          let defaultError = this._def.errorMap?.(issue2, ctx).message ?? ctx.defaultError;
          return issue2.code === "unrecognized_keys" ? {
            message: errorUtil.errToObj(message).message ?? defaultError
          } : {
            message: defaultError
          };
        }
      } : {}
    });
  }
  strip() {
    return new _ZodObject({
      ...this._def,
      unknownKeys: "strip"
    });
  }
  passthrough() {
    return new _ZodObject({
      ...this._def,
      unknownKeys: "passthrough"
    });
  }
  // const AugmentFactory =
  //   <Def extends ZodObjectDef>(def: Def) =>
  //   <Augmentation extends ZodRawShape>(
  //     augmentation: Augmentation
  //   ): ZodObject<
  //     extendShape<ReturnType<Def["shape"]>, Augmentation>,
  //     Def["unknownKeys"],
  //     Def["catchall"]
  //   > => {
  //     return new ZodObject({
  //       ...def,
  //       shape: () => ({
  //         ...def.shape(),
  //         ...augmentation,
  //       }),
  //     }) as any;
  //   };
  extend(augmentation) {
    return new _ZodObject({
      ...this._def,
      shape: () => ({
        ...this._def.shape(),
        ...augmentation
      })
    });
  }
  /**
   * Prior to zod@1.0.12 there was a bug in the
   * inferred type of merged objects. Please
   * upgrade if you are experiencing issues.
   */
  merge(merging) {
    return new _ZodObject({
      unknownKeys: merging._def.unknownKeys,
      catchall: merging._def.catchall,
      shape: () => ({
        ...this._def.shape(),
        ...merging._def.shape()
      }),
      typeName: ZodFirstPartyTypeKind.ZodObject
    });
  }
  // merge<
  //   Incoming extends AnyZodObject,
  //   Augmentation extends Incoming["shape"],
  //   NewOutput extends {
  //     [k in keyof Augmentation | keyof Output]: k extends keyof Augmentation
  //       ? Augmentation[k]["_output"]
  //       : k extends keyof Output
  //       ? Output[k]
  //       : never;
  //   },
  //   NewInput extends {
  //     [k in keyof Augmentation | keyof Input]: k extends keyof Augmentation
  //       ? Augmentation[k]["_input"]
  //       : k extends keyof Input
  //       ? Input[k]
  //       : never;
  //   }
  // >(
  //   merging: Incoming
  // ): ZodObject<
  //   extendShape<T, ReturnType<Incoming["_def"]["shape"]>>,
  //   Incoming["_def"]["unknownKeys"],
  //   Incoming["_def"]["catchall"],
  //   NewOutput,
  //   NewInput
  // > {
  //   const merged: any = new ZodObject({
  //     unknownKeys: merging._def.unknownKeys,
  //     catchall: merging._def.catchall,
  //     shape: () =>
  //       objectUtil.mergeShapes(this._def.shape(), merging._def.shape()),
  //     typeName: ZodFirstPartyTypeKind.ZodObject,
  //   }) as any;
  //   return merged;
  // }
  setKey(key, schema) {
    return this.augment({ [key]: schema });
  }
  // merge<Incoming extends AnyZodObject>(
  //   merging: Incoming
  // ): //ZodObject<T & Incoming["_shape"], UnknownKeys, Catchall> = (merging) => {
  // ZodObject<
  //   extendShape<T, ReturnType<Incoming["_def"]["shape"]>>,
  //   Incoming["_def"]["unknownKeys"],
  //   Incoming["_def"]["catchall"]
  // > {
  //   // const mergedShape = objectUtil.mergeShapes(
  //   //   this._def.shape(),
  //   //   merging._def.shape()
  //   // );
  //   const merged: any = new ZodObject({
  //     unknownKeys: merging._def.unknownKeys,
  //     catchall: merging._def.catchall,
  //     shape: () =>
  //       objectUtil.mergeShapes(this._def.shape(), merging._def.shape()),
  //     typeName: ZodFirstPartyTypeKind.ZodObject,
  //   }) as any;
  //   return merged;
  // }
  catchall(index) {
    return new _ZodObject({
      ...this._def,
      catchall: index
    });
  }
  pick(mask) {
    let shape = {};
    for (let key of util.objectKeys(mask))
      mask[key] && this.shape[key] && (shape[key] = this.shape[key]);
    return new _ZodObject({
      ...this._def,
      shape: () => shape
    });
  }
  omit(mask) {
    let shape = {};
    for (let key of util.objectKeys(this.shape))
      mask[key] || (shape[key] = this.shape[key]);
    return new _ZodObject({
      ...this._def,
      shape: () => shape
    });
  }
  /**
   * @deprecated
   */
  deepPartial() {
    return deepPartialify(this);
  }
  partial(mask) {
    let newShape = {};
    for (let key of util.objectKeys(this.shape)) {
      let fieldSchema = this.shape[key];
      mask && !mask[key] ? newShape[key] = fieldSchema : newShape[key] = fieldSchema.optional();
    }
    return new _ZodObject({
      ...this._def,
      shape: () => newShape
    });
  }
  required(mask) {
    let newShape = {};
    for (let key of util.objectKeys(this.shape))
      if (mask && !mask[key])
        newShape[key] = this.shape[key];
      else {
        let newField = this.shape[key];
        for (; newField instanceof ZodOptional; )
          newField = newField._def.innerType;
        newShape[key] = newField;
      }
    return new _ZodObject({
      ...this._def,
      shape: () => newShape
    });
  }
  keyof() {
    return createZodEnum(util.objectKeys(this.shape));
  }
};
ZodObject.create = (shape, params) => new ZodObject({
  shape: () => shape,
  unknownKeys: "strip",
  catchall: ZodNever.create(),
  typeName: ZodFirstPartyTypeKind.ZodObject,
  ...processCreateParams(params)
});
ZodObject.strictCreate = (shape, params) => new ZodObject({
  shape: () => shape,
  unknownKeys: "strict",
  catchall: ZodNever.create(),
  typeName: ZodFirstPartyTypeKind.ZodObject,
  ...processCreateParams(params)
});
ZodObject.lazycreate = (shape, params) => new ZodObject({
  shape,
  unknownKeys: "strip",
  catchall: ZodNever.create(),
  typeName: ZodFirstPartyTypeKind.ZodObject,
  ...processCreateParams(params)
});
var ZodUnion = class extends ZodType {
  _parse(input) {
    let { ctx } = this._processInputParams(input), options = this._def.options;
    function handleResults(results) {
      for (let result of results)
        if (result.result.status === "valid")
          return result.result;
      for (let result of results)
        if (result.result.status === "dirty")
          return ctx.common.issues.push(...result.ctx.common.issues), result.result;
      let unionErrors = results.map((result) => new ZodError(result.ctx.common.issues));
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_union,
        unionErrors
      }), INVALID;
    }
    if (ctx.common.async)
      return Promise.all(options.map(async (option) => {
        let childCtx = {
          ...ctx,
          common: {
            ...ctx.common,
            issues: []
          },
          parent: null
        };
        return {
          result: await option._parseAsync({
            data: ctx.data,
            path: ctx.path,
            parent: childCtx
          }),
          ctx: childCtx
        };
      })).then(handleResults);
    {
      let dirty, issues = [];
      for (let option of options) {
        let childCtx = {
          ...ctx,
          common: {
            ...ctx.common,
            issues: []
          },
          parent: null
        }, result = option._parseSync({
          data: ctx.data,
          path: ctx.path,
          parent: childCtx
        });
        if (result.status === "valid")
          return result;
        result.status === "dirty" && !dirty && (dirty = { result, ctx: childCtx }), childCtx.common.issues.length && issues.push(childCtx.common.issues);
      }
      if (dirty)
        return ctx.common.issues.push(...dirty.ctx.common.issues), dirty.result;
      let unionErrors = issues.map((issues2) => new ZodError(issues2));
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_union,
        unionErrors
      }), INVALID;
    }
  }
  get options() {
    return this._def.options;
  }
};
ZodUnion.create = (types, params) => new ZodUnion({
  options: types,
  typeName: ZodFirstPartyTypeKind.ZodUnion,
  ...processCreateParams(params)
});
var getDiscriminator = (type) => type instanceof ZodLazy ? getDiscriminator(type.schema) : type instanceof ZodEffects ? getDiscriminator(type.innerType()) : type instanceof ZodLiteral ? [type.value] : type instanceof ZodEnum ? type.options : type instanceof ZodNativeEnum ? util.objectValues(type.enum) : type instanceof ZodDefault ? getDiscriminator(type._def.innerType) : type instanceof ZodUndefined ? [void 0] : type instanceof ZodNull ? [null] : type instanceof ZodOptional ? [void 0, ...getDiscriminator(type.unwrap())] : type instanceof ZodNullable ? [null, ...getDiscriminator(type.unwrap())] : type instanceof ZodBranded || type instanceof ZodReadonly ? getDiscriminator(type.unwrap()) : type instanceof ZodCatch ? getDiscriminator(type._def.innerType) : [], ZodDiscriminatedUnion = class _ZodDiscriminatedUnion extends ZodType {
  _parse(input) {
    let { ctx } = this._processInputParams(input);
    if (ctx.parsedType !== ZodParsedType.object)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.object,
        received: ctx.parsedType
      }), INVALID;
    let discriminator = this.discriminator, discriminatorValue = ctx.data[discriminator], option = this.optionsMap.get(discriminatorValue);
    return option ? ctx.common.async ? option._parseAsync({
      data: ctx.data,
      path: ctx.path,
      parent: ctx
    }) : option._parseSync({
      data: ctx.data,
      path: ctx.path,
      parent: ctx
    }) : (addIssueToContext(ctx, {
      code: ZodIssueCode.invalid_union_discriminator,
      options: Array.from(this.optionsMap.keys()),
      path: [discriminator]
    }), INVALID);
  }
  get discriminator() {
    return this._def.discriminator;
  }
  get options() {
    return this._def.options;
  }
  get optionsMap() {
    return this._def.optionsMap;
  }
  /**
   * The constructor of the discriminated union schema. Its behaviour is very similar to that of the normal z.union() constructor.
   * However, it only allows a union of objects, all of which need to share a discriminator property. This property must
   * have a different value for each object in the union.
   * @param discriminator the name of the discriminator property
   * @param types an array of object schemas
   * @param params
   */
  static create(discriminator, options, params) {
    let optionsMap = /* @__PURE__ */ new Map();
    for (let type of options) {
      let discriminatorValues = getDiscriminator(type.shape[discriminator]);
      if (!discriminatorValues.length)
        throw new Error(`A discriminator value for key \`${discriminator}\` could not be extracted from all schema options`);
      for (let value of discriminatorValues) {
        if (optionsMap.has(value))
          throw new Error(`Discriminator property ${String(discriminator)} has duplicate value ${String(value)}`);
        optionsMap.set(value, type);
      }
    }
    return new _ZodDiscriminatedUnion({
      typeName: ZodFirstPartyTypeKind.ZodDiscriminatedUnion,
      discriminator,
      options,
      optionsMap,
      ...processCreateParams(params)
    });
  }
};
function mergeValues(a, b) {
  let aType = getParsedType(a), bType = getParsedType(b);
  if (a === b)
    return { valid: !0, data: a };
  if (aType === ZodParsedType.object && bType === ZodParsedType.object) {
    let bKeys = util.objectKeys(b), sharedKeys = util.objectKeys(a).filter((key) => bKeys.indexOf(key) !== -1), newObj = { ...a, ...b };
    for (let key of sharedKeys) {
      let sharedValue = mergeValues(a[key], b[key]);
      if (!sharedValue.valid)
        return { valid: !1 };
      newObj[key] = sharedValue.data;
    }
    return { valid: !0, data: newObj };
  } else if (aType === ZodParsedType.array && bType === ZodParsedType.array) {
    if (a.length !== b.length)
      return { valid: !1 };
    let newArray = [];
    for (let index = 0; index < a.length; index++) {
      let itemA = a[index], itemB = b[index], sharedValue = mergeValues(itemA, itemB);
      if (!sharedValue.valid)
        return { valid: !1 };
      newArray.push(sharedValue.data);
    }
    return { valid: !0, data: newArray };
  } else return aType === ZodParsedType.date && bType === ZodParsedType.date && +a == +b ? { valid: !0, data: a } : { valid: !1 };
}
var ZodIntersection = class extends ZodType {
  _parse(input) {
    let { status, ctx } = this._processInputParams(input), handleParsed = (parsedLeft, parsedRight) => {
      if (isAborted(parsedLeft) || isAborted(parsedRight))
        return INVALID;
      let merged = mergeValues(parsedLeft.value, parsedRight.value);
      return merged.valid ? ((isDirty(parsedLeft) || isDirty(parsedRight)) && status.dirty(), { status: status.value, value: merged.data }) : (addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_intersection_types
      }), INVALID);
    };
    return ctx.common.async ? Promise.all([
      this._def.left._parseAsync({
        data: ctx.data,
        path: ctx.path,
        parent: ctx
      }),
      this._def.right._parseAsync({
        data: ctx.data,
        path: ctx.path,
        parent: ctx
      })
    ]).then(([left, right]) => handleParsed(left, right)) : handleParsed(this._def.left._parseSync({
      data: ctx.data,
      path: ctx.path,
      parent: ctx
    }), this._def.right._parseSync({
      data: ctx.data,
      path: ctx.path,
      parent: ctx
    }));
  }
};
ZodIntersection.create = (left, right, params) => new ZodIntersection({
  left,
  right,
  typeName: ZodFirstPartyTypeKind.ZodIntersection,
  ...processCreateParams(params)
});
var ZodTuple = class _ZodTuple extends ZodType {
  _parse(input) {
    let { status, ctx } = this._processInputParams(input);
    if (ctx.parsedType !== ZodParsedType.array)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.array,
        received: ctx.parsedType
      }), INVALID;
    if (ctx.data.length < this._def.items.length)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.too_small,
        minimum: this._def.items.length,
        inclusive: !0,
        exact: !1,
        type: "array"
      }), INVALID;
    !this._def.rest && ctx.data.length > this._def.items.length && (addIssueToContext(ctx, {
      code: ZodIssueCode.too_big,
      maximum: this._def.items.length,
      inclusive: !0,
      exact: !1,
      type: "array"
    }), status.dirty());
    let items = [...ctx.data].map((item, itemIndex) => {
      let schema = this._def.items[itemIndex] || this._def.rest;
      return schema ? schema._parse(new ParseInputLazyPath(ctx, item, ctx.path, itemIndex)) : null;
    }).filter((x) => !!x);
    return ctx.common.async ? Promise.all(items).then((results) => ParseStatus.mergeArray(status, results)) : ParseStatus.mergeArray(status, items);
  }
  get items() {
    return this._def.items;
  }
  rest(rest) {
    return new _ZodTuple({
      ...this._def,
      rest
    });
  }
};
ZodTuple.create = (schemas, params) => {
  if (!Array.isArray(schemas))
    throw new Error("You must pass an array of schemas to z.tuple([ ... ])");
  return new ZodTuple({
    items: schemas,
    typeName: ZodFirstPartyTypeKind.ZodTuple,
    rest: null,
    ...processCreateParams(params)
  });
};
var ZodRecord = class _ZodRecord extends ZodType {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(input) {
    let { status, ctx } = this._processInputParams(input);
    if (ctx.parsedType !== ZodParsedType.object)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.object,
        received: ctx.parsedType
      }), INVALID;
    let pairs = [], keyType = this._def.keyType, valueType = this._def.valueType;
    for (let key in ctx.data)
      pairs.push({
        key: keyType._parse(new ParseInputLazyPath(ctx, key, ctx.path, key)),
        value: valueType._parse(new ParseInputLazyPath(ctx, ctx.data[key], ctx.path, key)),
        alwaysSet: key in ctx.data
      });
    return ctx.common.async ? ParseStatus.mergeObjectAsync(status, pairs) : ParseStatus.mergeObjectSync(status, pairs);
  }
  get element() {
    return this._def.valueType;
  }
  static create(first, second, third) {
    return second instanceof ZodType ? new _ZodRecord({
      keyType: first,
      valueType: second,
      typeName: ZodFirstPartyTypeKind.ZodRecord,
      ...processCreateParams(third)
    }) : new _ZodRecord({
      keyType: ZodString.create(),
      valueType: first,
      typeName: ZodFirstPartyTypeKind.ZodRecord,
      ...processCreateParams(second)
    });
  }
}, ZodMap = class extends ZodType {
  get keySchema() {
    return this._def.keyType;
  }
  get valueSchema() {
    return this._def.valueType;
  }
  _parse(input) {
    let { status, ctx } = this._processInputParams(input);
    if (ctx.parsedType !== ZodParsedType.map)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.map,
        received: ctx.parsedType
      }), INVALID;
    let keyType = this._def.keyType, valueType = this._def.valueType, pairs = [...ctx.data.entries()].map(([key, value], index) => ({
      key: keyType._parse(new ParseInputLazyPath(ctx, key, ctx.path, [index, "key"])),
      value: valueType._parse(new ParseInputLazyPath(ctx, value, ctx.path, [index, "value"]))
    }));
    if (ctx.common.async) {
      let finalMap = /* @__PURE__ */ new Map();
      return Promise.resolve().then(async () => {
        for (let pair of pairs) {
          let key = await pair.key, value = await pair.value;
          if (key.status === "aborted" || value.status === "aborted")
            return INVALID;
          (key.status === "dirty" || value.status === "dirty") && status.dirty(), finalMap.set(key.value, value.value);
        }
        return { status: status.value, value: finalMap };
      });
    } else {
      let finalMap = /* @__PURE__ */ new Map();
      for (let pair of pairs) {
        let key = pair.key, value = pair.value;
        if (key.status === "aborted" || value.status === "aborted")
          return INVALID;
        (key.status === "dirty" || value.status === "dirty") && status.dirty(), finalMap.set(key.value, value.value);
      }
      return { status: status.value, value: finalMap };
    }
  }
};
ZodMap.create = (keyType, valueType, params) => new ZodMap({
  valueType,
  keyType,
  typeName: ZodFirstPartyTypeKind.ZodMap,
  ...processCreateParams(params)
});
var ZodSet = class _ZodSet extends ZodType {
  _parse(input) {
    let { status, ctx } = this._processInputParams(input);
    if (ctx.parsedType !== ZodParsedType.set)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.set,
        received: ctx.parsedType
      }), INVALID;
    let def = this._def;
    def.minSize !== null && ctx.data.size < def.minSize.value && (addIssueToContext(ctx, {
      code: ZodIssueCode.too_small,
      minimum: def.minSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: def.minSize.message
    }), status.dirty()), def.maxSize !== null && ctx.data.size > def.maxSize.value && (addIssueToContext(ctx, {
      code: ZodIssueCode.too_big,
      maximum: def.maxSize.value,
      type: "set",
      inclusive: !0,
      exact: !1,
      message: def.maxSize.message
    }), status.dirty());
    let valueType = this._def.valueType;
    function finalizeSet(elements2) {
      let parsedSet = /* @__PURE__ */ new Set();
      for (let element of elements2) {
        if (element.status === "aborted")
          return INVALID;
        element.status === "dirty" && status.dirty(), parsedSet.add(element.value);
      }
      return { status: status.value, value: parsedSet };
    }
    let elements = [...ctx.data.values()].map((item, i) => valueType._parse(new ParseInputLazyPath(ctx, item, ctx.path, i)));
    return ctx.common.async ? Promise.all(elements).then((elements2) => finalizeSet(elements2)) : finalizeSet(elements);
  }
  min(minSize, message) {
    return new _ZodSet({
      ...this._def,
      minSize: { value: minSize, message: errorUtil.toString(message) }
    });
  }
  max(maxSize, message) {
    return new _ZodSet({
      ...this._def,
      maxSize: { value: maxSize, message: errorUtil.toString(message) }
    });
  }
  size(size, message) {
    return this.min(size, message).max(size, message);
  }
  nonempty(message) {
    return this.min(1, message);
  }
};
ZodSet.create = (valueType, params) => new ZodSet({
  valueType,
  minSize: null,
  maxSize: null,
  typeName: ZodFirstPartyTypeKind.ZodSet,
  ...processCreateParams(params)
});
var ZodFunction = class _ZodFunction extends ZodType {
  constructor() {
    super(...arguments), this.validate = this.implement;
  }
  _parse(input) {
    let { ctx } = this._processInputParams(input);
    if (ctx.parsedType !== ZodParsedType.function)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.function,
        received: ctx.parsedType
      }), INVALID;
    function makeArgsIssue(args, error2) {
      return makeIssue({
        data: args,
        path: ctx.path,
        errorMaps: [ctx.common.contextualErrorMap, ctx.schemaErrorMap, getErrorMap(), en_default].filter((x) => !!x),
        issueData: {
          code: ZodIssueCode.invalid_arguments,
          argumentsError: error2
        }
      });
    }
    function makeReturnsIssue(returns, error2) {
      return makeIssue({
        data: returns,
        path: ctx.path,
        errorMaps: [ctx.common.contextualErrorMap, ctx.schemaErrorMap, getErrorMap(), en_default].filter((x) => !!x),
        issueData: {
          code: ZodIssueCode.invalid_return_type,
          returnTypeError: error2
        }
      });
    }
    let params = { errorMap: ctx.common.contextualErrorMap }, fn = ctx.data;
    if (this._def.returns instanceof ZodPromise) {
      let me = this;
      return OK(async function(...args) {
        let error2 = new ZodError([]), parsedArgs = await me._def.args.parseAsync(args, params).catch((e) => {
          throw error2.addIssue(makeArgsIssue(args, e)), error2;
        }), result = await Reflect.apply(fn, this, parsedArgs);
        return await me._def.returns._def.type.parseAsync(result, params).catch((e) => {
          throw error2.addIssue(makeReturnsIssue(result, e)), error2;
        });
      });
    } else {
      let me = this;
      return OK(function(...args) {
        let parsedArgs = me._def.args.safeParse(args, params);
        if (!parsedArgs.success)
          throw new ZodError([makeArgsIssue(args, parsedArgs.error)]);
        let result = Reflect.apply(fn, this, parsedArgs.data), parsedReturns = me._def.returns.safeParse(result, params);
        if (!parsedReturns.success)
          throw new ZodError([makeReturnsIssue(result, parsedReturns.error)]);
        return parsedReturns.data;
      });
    }
  }
  parameters() {
    return this._def.args;
  }
  returnType() {
    return this._def.returns;
  }
  args(...items) {
    return new _ZodFunction({
      ...this._def,
      args: ZodTuple.create(items).rest(ZodUnknown.create())
    });
  }
  returns(returnType) {
    return new _ZodFunction({
      ...this._def,
      returns: returnType
    });
  }
  implement(func) {
    return this.parse(func);
  }
  strictImplement(func) {
    return this.parse(func);
  }
  static create(args, returns, params) {
    return new _ZodFunction({
      args: args || ZodTuple.create([]).rest(ZodUnknown.create()),
      returns: returns || ZodUnknown.create(),
      typeName: ZodFirstPartyTypeKind.ZodFunction,
      ...processCreateParams(params)
    });
  }
}, ZodLazy = class extends ZodType {
  get schema() {
    return this._def.getter();
  }
  _parse(input) {
    let { ctx } = this._processInputParams(input);
    return this._def.getter()._parse({ data: ctx.data, path: ctx.path, parent: ctx });
  }
};
ZodLazy.create = (getter, params) => new ZodLazy({
  getter,
  typeName: ZodFirstPartyTypeKind.ZodLazy,
  ...processCreateParams(params)
});
var ZodLiteral = class extends ZodType {
  _parse(input) {
    if (input.data !== this._def.value) {
      let ctx = this._getOrReturnCtx(input);
      return addIssueToContext(ctx, {
        received: ctx.data,
        code: ZodIssueCode.invalid_literal,
        expected: this._def.value
      }), INVALID;
    }
    return { status: "valid", value: input.data };
  }
  get value() {
    return this._def.value;
  }
};
ZodLiteral.create = (value, params) => new ZodLiteral({
  value,
  typeName: ZodFirstPartyTypeKind.ZodLiteral,
  ...processCreateParams(params)
});
function createZodEnum(values, params) {
  return new ZodEnum({
    values,
    typeName: ZodFirstPartyTypeKind.ZodEnum,
    ...processCreateParams(params)
  });
}
var ZodEnum = class _ZodEnum extends ZodType {
  _parse(input) {
    if (typeof input.data != "string") {
      let ctx = this._getOrReturnCtx(input), expectedValues = this._def.values;
      return addIssueToContext(ctx, {
        expected: util.joinValues(expectedValues),
        received: ctx.parsedType,
        code: ZodIssueCode.invalid_type
      }), INVALID;
    }
    if (this._cache || (this._cache = new Set(this._def.values)), !this._cache.has(input.data)) {
      let ctx = this._getOrReturnCtx(input), expectedValues = this._def.values;
      return addIssueToContext(ctx, {
        received: ctx.data,
        code: ZodIssueCode.invalid_enum_value,
        options: expectedValues
      }), INVALID;
    }
    return OK(input.data);
  }
  get options() {
    return this._def.values;
  }
  get enum() {
    let enumValues = {};
    for (let val of this._def.values)
      enumValues[val] = val;
    return enumValues;
  }
  get Values() {
    let enumValues = {};
    for (let val of this._def.values)
      enumValues[val] = val;
    return enumValues;
  }
  get Enum() {
    let enumValues = {};
    for (let val of this._def.values)
      enumValues[val] = val;
    return enumValues;
  }
  extract(values, newDef = this._def) {
    return _ZodEnum.create(values, {
      ...this._def,
      ...newDef
    });
  }
  exclude(values, newDef = this._def) {
    return _ZodEnum.create(this.options.filter((opt) => !values.includes(opt)), {
      ...this._def,
      ...newDef
    });
  }
};
ZodEnum.create = createZodEnum;
var ZodNativeEnum = class extends ZodType {
  _parse(input) {
    let nativeEnumValues = util.getValidEnumValues(this._def.values), ctx = this._getOrReturnCtx(input);
    if (ctx.parsedType !== ZodParsedType.string && ctx.parsedType !== ZodParsedType.number) {
      let expectedValues = util.objectValues(nativeEnumValues);
      return addIssueToContext(ctx, {
        expected: util.joinValues(expectedValues),
        received: ctx.parsedType,
        code: ZodIssueCode.invalid_type
      }), INVALID;
    }
    if (this._cache || (this._cache = new Set(util.getValidEnumValues(this._def.values))), !this._cache.has(input.data)) {
      let expectedValues = util.objectValues(nativeEnumValues);
      return addIssueToContext(ctx, {
        received: ctx.data,
        code: ZodIssueCode.invalid_enum_value,
        options: expectedValues
      }), INVALID;
    }
    return OK(input.data);
  }
  get enum() {
    return this._def.values;
  }
};
ZodNativeEnum.create = (values, params) => new ZodNativeEnum({
  values,
  typeName: ZodFirstPartyTypeKind.ZodNativeEnum,
  ...processCreateParams(params)
});
var ZodPromise = class extends ZodType {
  unwrap() {
    return this._def.type;
  }
  _parse(input) {
    let { ctx } = this._processInputParams(input);
    if (ctx.parsedType !== ZodParsedType.promise && ctx.common.async === !1)
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.promise,
        received: ctx.parsedType
      }), INVALID;
    let promisified = ctx.parsedType === ZodParsedType.promise ? ctx.data : Promise.resolve(ctx.data);
    return OK(promisified.then((data) => this._def.type.parseAsync(data, {
      path: ctx.path,
      errorMap: ctx.common.contextualErrorMap
    })));
  }
};
ZodPromise.create = (schema, params) => new ZodPromise({
  type: schema,
  typeName: ZodFirstPartyTypeKind.ZodPromise,
  ...processCreateParams(params)
});
var ZodEffects = class extends ZodType {
  innerType() {
    return this._def.schema;
  }
  sourceType() {
    return this._def.schema._def.typeName === ZodFirstPartyTypeKind.ZodEffects ? this._def.schema.sourceType() : this._def.schema;
  }
  _parse(input) {
    let { status, ctx } = this._processInputParams(input), effect = this._def.effect || null, checkCtx = {
      addIssue: (arg) => {
        addIssueToContext(ctx, arg), arg.fatal ? status.abort() : status.dirty();
      },
      get path() {
        return ctx.path;
      }
    };
    if (checkCtx.addIssue = checkCtx.addIssue.bind(checkCtx), effect.type === "preprocess") {
      let processed = effect.transform(ctx.data, checkCtx);
      if (ctx.common.async)
        return Promise.resolve(processed).then(async (processed2) => {
          if (status.value === "aborted")
            return INVALID;
          let result = await this._def.schema._parseAsync({
            data: processed2,
            path: ctx.path,
            parent: ctx
          });
          return result.status === "aborted" ? INVALID : result.status === "dirty" ? DIRTY(result.value) : status.value === "dirty" ? DIRTY(result.value) : result;
        });
      {
        if (status.value === "aborted")
          return INVALID;
        let result = this._def.schema._parseSync({
          data: processed,
          path: ctx.path,
          parent: ctx
        });
        return result.status === "aborted" ? INVALID : result.status === "dirty" ? DIRTY(result.value) : status.value === "dirty" ? DIRTY(result.value) : result;
      }
    }
    if (effect.type === "refinement") {
      let executeRefinement = (acc) => {
        let result = effect.refinement(acc, checkCtx);
        if (ctx.common.async)
          return Promise.resolve(result);
        if (result instanceof Promise)
          throw new Error("Async refinement encountered during synchronous parse operation. Use .parseAsync instead.");
        return acc;
      };
      if (ctx.common.async === !1) {
        let inner = this._def.schema._parseSync({
          data: ctx.data,
          path: ctx.path,
          parent: ctx
        });
        return inner.status === "aborted" ? INVALID : (inner.status === "dirty" && status.dirty(), executeRefinement(inner.value), { status: status.value, value: inner.value });
      } else
        return this._def.schema._parseAsync({ data: ctx.data, path: ctx.path, parent: ctx }).then((inner) => inner.status === "aborted" ? INVALID : (inner.status === "dirty" && status.dirty(), executeRefinement(inner.value).then(() => ({ status: status.value, value: inner.value }))));
    }
    if (effect.type === "transform")
      if (ctx.common.async === !1) {
        let base = this._def.schema._parseSync({
          data: ctx.data,
          path: ctx.path,
          parent: ctx
        });
        if (!isValid(base))
          return INVALID;
        let result = effect.transform(base.value, checkCtx);
        if (result instanceof Promise)
          throw new Error("Asynchronous transform encountered during synchronous parse operation. Use .parseAsync instead.");
        return { status: status.value, value: result };
      } else
        return this._def.schema._parseAsync({ data: ctx.data, path: ctx.path, parent: ctx }).then((base) => isValid(base) ? Promise.resolve(effect.transform(base.value, checkCtx)).then((result) => ({
          status: status.value,
          value: result
        })) : INVALID);
    util.assertNever(effect);
  }
};
ZodEffects.create = (schema, effect, params) => new ZodEffects({
  schema,
  typeName: ZodFirstPartyTypeKind.ZodEffects,
  effect,
  ...processCreateParams(params)
});
ZodEffects.createWithPreprocess = (preprocess2, schema, params) => new ZodEffects({
  schema,
  effect: { type: "preprocess", transform: preprocess2 },
  typeName: ZodFirstPartyTypeKind.ZodEffects,
  ...processCreateParams(params)
});
var ZodOptional = class extends ZodType {
  _parse(input) {
    return this._getType(input) === ZodParsedType.undefined ? OK(void 0) : this._def.innerType._parse(input);
  }
  unwrap() {
    return this._def.innerType;
  }
};
ZodOptional.create = (type, params) => new ZodOptional({
  innerType: type,
  typeName: ZodFirstPartyTypeKind.ZodOptional,
  ...processCreateParams(params)
});
var ZodNullable = class extends ZodType {
  _parse(input) {
    return this._getType(input) === ZodParsedType.null ? OK(null) : this._def.innerType._parse(input);
  }
  unwrap() {
    return this._def.innerType;
  }
};
ZodNullable.create = (type, params) => new ZodNullable({
  innerType: type,
  typeName: ZodFirstPartyTypeKind.ZodNullable,
  ...processCreateParams(params)
});
var ZodDefault = class extends ZodType {
  _parse(input) {
    let { ctx } = this._processInputParams(input), data = ctx.data;
    return ctx.parsedType === ZodParsedType.undefined && (data = this._def.defaultValue()), this._def.innerType._parse({
      data,
      path: ctx.path,
      parent: ctx
    });
  }
  removeDefault() {
    return this._def.innerType;
  }
};
ZodDefault.create = (type, params) => new ZodDefault({
  innerType: type,
  typeName: ZodFirstPartyTypeKind.ZodDefault,
  defaultValue: typeof params.default == "function" ? params.default : () => params.default,
  ...processCreateParams(params)
});
var ZodCatch = class extends ZodType {
  _parse(input) {
    let { ctx } = this._processInputParams(input), newCtx = {
      ...ctx,
      common: {
        ...ctx.common,
        issues: []
      }
    }, result = this._def.innerType._parse({
      data: newCtx.data,
      path: newCtx.path,
      parent: {
        ...newCtx
      }
    });
    return isAsync(result) ? result.then((result2) => ({
      status: "valid",
      value: result2.status === "valid" ? result2.value : this._def.catchValue({
        get error() {
          return new ZodError(newCtx.common.issues);
        },
        input: newCtx.data
      })
    })) : {
      status: "valid",
      value: result.status === "valid" ? result.value : this._def.catchValue({
        get error() {
          return new ZodError(newCtx.common.issues);
        },
        input: newCtx.data
      })
    };
  }
  removeCatch() {
    return this._def.innerType;
  }
};
ZodCatch.create = (type, params) => new ZodCatch({
  innerType: type,
  typeName: ZodFirstPartyTypeKind.ZodCatch,
  catchValue: typeof params.catch == "function" ? params.catch : () => params.catch,
  ...processCreateParams(params)
});
var ZodNaN = class extends ZodType {
  _parse(input) {
    if (this._getType(input) !== ZodParsedType.nan) {
      let ctx = this._getOrReturnCtx(input);
      return addIssueToContext(ctx, {
        code: ZodIssueCode.invalid_type,
        expected: ZodParsedType.nan,
        received: ctx.parsedType
      }), INVALID;
    }
    return { status: "valid", value: input.data };
  }
};
ZodNaN.create = (params) => new ZodNaN({
  typeName: ZodFirstPartyTypeKind.ZodNaN,
  ...processCreateParams(params)
});
var BRAND = Symbol("zod_brand"), ZodBranded = class extends ZodType {
  _parse(input) {
    let { ctx } = this._processInputParams(input), data = ctx.data;
    return this._def.type._parse({
      data,
      path: ctx.path,
      parent: ctx
    });
  }
  unwrap() {
    return this._def.type;
  }
}, ZodPipeline = class _ZodPipeline extends ZodType {
  _parse(input) {
    let { status, ctx } = this._processInputParams(input);
    if (ctx.common.async)
      return (async () => {
        let inResult = await this._def.in._parseAsync({
          data: ctx.data,
          path: ctx.path,
          parent: ctx
        });
        return inResult.status === "aborted" ? INVALID : inResult.status === "dirty" ? (status.dirty(), DIRTY(inResult.value)) : this._def.out._parseAsync({
          data: inResult.value,
          path: ctx.path,
          parent: ctx
        });
      })();
    {
      let inResult = this._def.in._parseSync({
        data: ctx.data,
        path: ctx.path,
        parent: ctx
      });
      return inResult.status === "aborted" ? INVALID : inResult.status === "dirty" ? (status.dirty(), {
        status: "dirty",
        value: inResult.value
      }) : this._def.out._parseSync({
        data: inResult.value,
        path: ctx.path,
        parent: ctx
      });
    }
  }
  static create(a, b) {
    return new _ZodPipeline({
      in: a,
      out: b,
      typeName: ZodFirstPartyTypeKind.ZodPipeline
    });
  }
}, ZodReadonly = class extends ZodType {
  _parse(input) {
    let result = this._def.innerType._parse(input), freeze = (data) => (isValid(data) && (data.value = Object.freeze(data.value)), data);
    return isAsync(result) ? result.then((data) => freeze(data)) : freeze(result);
  }
  unwrap() {
    return this._def.innerType;
  }
};
ZodReadonly.create = (type, params) => new ZodReadonly({
  innerType: type,
  typeName: ZodFirstPartyTypeKind.ZodReadonly,
  ...processCreateParams(params)
});
function cleanParams(params, data) {
  let p = typeof params == "function" ? params(data) : typeof params == "string" ? { message: params } : params;
  return typeof p == "string" ? { message: p } : p;
}
function custom(check2, _params = {}, fatal) {
  return check2 ? ZodAny.create().superRefine((data, ctx) => {
    let r = check2(data);
    if (r instanceof Promise)
      return r.then((r2) => {
        if (!r2) {
          let params = cleanParams(_params, data), _fatal = params.fatal ?? fatal ?? !0;
          ctx.addIssue({ code: "custom", ...params, fatal: _fatal });
        }
      });
    if (!r) {
      let params = cleanParams(_params, data), _fatal = params.fatal ?? fatal ?? !0;
      ctx.addIssue({ code: "custom", ...params, fatal: _fatal });
    }
  }) : ZodAny.create();
}
var late = {
  object: ZodObject.lazycreate
}, ZodFirstPartyTypeKind;
(function(ZodFirstPartyTypeKind2) {
  ZodFirstPartyTypeKind2.ZodString = "ZodString", ZodFirstPartyTypeKind2.ZodNumber = "ZodNumber", ZodFirstPartyTypeKind2.ZodNaN = "ZodNaN", ZodFirstPartyTypeKind2.ZodBigInt = "ZodBigInt", ZodFirstPartyTypeKind2.ZodBoolean = "ZodBoolean", ZodFirstPartyTypeKind2.ZodDate = "ZodDate", ZodFirstPartyTypeKind2.ZodSymbol = "ZodSymbol", ZodFirstPartyTypeKind2.ZodUndefined = "ZodUndefined", ZodFirstPartyTypeKind2.ZodNull = "ZodNull", ZodFirstPartyTypeKind2.ZodAny = "ZodAny", ZodFirstPartyTypeKind2.ZodUnknown = "ZodUnknown", ZodFirstPartyTypeKind2.ZodNever = "ZodNever", ZodFirstPartyTypeKind2.ZodVoid = "ZodVoid", ZodFirstPartyTypeKind2.ZodArray = "ZodArray", ZodFirstPartyTypeKind2.ZodObject = "ZodObject", ZodFirstPartyTypeKind2.ZodUnion = "ZodUnion", ZodFirstPartyTypeKind2.ZodDiscriminatedUnion = "ZodDiscriminatedUnion", ZodFirstPartyTypeKind2.ZodIntersection = "ZodIntersection", ZodFirstPartyTypeKind2.ZodTuple = "ZodTuple", ZodFirstPartyTypeKind2.ZodRecord = "ZodRecord", ZodFirstPartyTypeKind2.ZodMap = "ZodMap", ZodFirstPartyTypeKind2.ZodSet = "ZodSet", ZodFirstPartyTypeKind2.ZodFunction = "ZodFunction", ZodFirstPartyTypeKind2.ZodLazy = "ZodLazy", ZodFirstPartyTypeKind2.ZodLiteral = "ZodLiteral", ZodFirstPartyTypeKind2.ZodEnum = "ZodEnum", ZodFirstPartyTypeKind2.ZodEffects = "ZodEffects", ZodFirstPartyTypeKind2.ZodNativeEnum = "ZodNativeEnum", ZodFirstPartyTypeKind2.ZodOptional = "ZodOptional", ZodFirstPartyTypeKind2.ZodNullable = "ZodNullable", ZodFirstPartyTypeKind2.ZodDefault = "ZodDefault", ZodFirstPartyTypeKind2.ZodCatch = "ZodCatch", ZodFirstPartyTypeKind2.ZodPromise = "ZodPromise", ZodFirstPartyTypeKind2.ZodBranded = "ZodBranded", ZodFirstPartyTypeKind2.ZodPipeline = "ZodPipeline", ZodFirstPartyTypeKind2.ZodReadonly = "ZodReadonly";
})(ZodFirstPartyTypeKind || (ZodFirstPartyTypeKind = {}));
var instanceOfType = (cls, params = {
  message: `Input not instance of ${cls.name}`
}) => custom((data) => data instanceof cls, params), stringType = ZodString.create, numberType = ZodNumber.create, nanType = ZodNaN.create, bigIntType = ZodBigInt.create, booleanType = ZodBoolean.create, dateType = ZodDate.create, symbolType = ZodSymbol.create, undefinedType = ZodUndefined.create, nullType = ZodNull.create, anyType = ZodAny.create, unknownType = ZodUnknown.create, neverType = ZodNever.create, voidType = ZodVoid.create, arrayType = ZodArray.create, objectType = ZodObject.create, strictObjectType = ZodObject.strictCreate, unionType = ZodUnion.create, discriminatedUnionType = ZodDiscriminatedUnion.create, intersectionType = ZodIntersection.create, tupleType = ZodTuple.create, recordType = ZodRecord.create, mapType = ZodMap.create, setType = ZodSet.create, functionType = ZodFunction.create, lazyType = ZodLazy.create, literalType = ZodLiteral.create, enumType = ZodEnum.create, nativeEnumType = ZodNativeEnum.create, promiseType = ZodPromise.create, effectsType = ZodEffects.create, optionalType = ZodOptional.create, nullableType = ZodNullable.create, preprocessType = ZodEffects.createWithPreprocess, pipelineType = ZodPipeline.create, ostring = () => stringType().optional(), onumber = () => numberType().optional(), oboolean = () => booleanType().optional(), coerce = {
  string: ((arg) => ZodString.create({ ...arg, coerce: !0 })),
  number: ((arg) => ZodNumber.create({ ...arg, coerce: !0 })),
  boolean: ((arg) => ZodBoolean.create({
    ...arg,
    coerce: !0
  })),
  bigint: ((arg) => ZodBigInt.create({ ...arg, coerce: !0 })),
  date: ((arg) => ZodDate.create({ ...arg, coerce: !0 }))
};
var NEVER = INVALID;

// node_modules/zod/v4/core/core.js
var NEVER2 = Object.freeze({
  status: "aborted"
});
// @__NO_SIDE_EFFECTS__
function $constructor(name, initializer3, params) {
  function init(inst, def) {
    var _a;
    Object.defineProperty(inst, "_zod", {
      value: inst._zod ?? {},
      enumerable: !1
    }), (_a = inst._zod).traits ?? (_a.traits = /* @__PURE__ */ new Set()), inst._zod.traits.add(name), initializer3(inst, def);
    for (let k in _.prototype)
      k in inst || Object.defineProperty(inst, k, { value: _.prototype[k].bind(inst) });
    inst._zod.constr = _, inst._zod.def = def;
  }
  let Parent = params?.Parent ?? Object;
  class Definition extends Parent {
  }
  Object.defineProperty(Definition, "name", { value: name });
  function _(def) {
    var _a;
    let inst = params?.Parent ? new Definition() : this;
    init(inst, def), (_a = inst._zod).deferred ?? (_a.deferred = []);
    for (let fn of inst._zod.deferred)
      fn();
    return inst;
  }
  return Object.defineProperty(_, "init", { value: init }), Object.defineProperty(_, Symbol.hasInstance, {
    value: (inst) => params?.Parent && inst instanceof params.Parent ? !0 : inst?._zod?.traits?.has(name)
  }), Object.defineProperty(_, "name", { value: name }), _;
}
var $brand = Symbol("zod_brand"), $ZodAsyncError = class extends Error {
  constructor() {
    super("Encountered Promise during synchronous parse. Use .parseAsync() instead.");
  }
}, globalConfig = {};
function config(newConfig) {
  return newConfig && Object.assign(globalConfig, newConfig), globalConfig;
}

// node_modules/zod/v4/core/util.js
var util_exports = {};
__export(util_exports, {
  BIGINT_FORMAT_RANGES: () => BIGINT_FORMAT_RANGES,
  Class: () => Class,
  NUMBER_FORMAT_RANGES: () => NUMBER_FORMAT_RANGES,
  aborted: () => aborted,
  allowsEval: () => allowsEval,
  assert: () => assert,
  assertEqual: () => assertEqual,
  assertIs: () => assertIs,
  assertNever: () => assertNever,
  assertNotEqual: () => assertNotEqual,
  assignProp: () => assignProp,
  cached: () => cached,
  captureStackTrace: () => captureStackTrace,
  cleanEnum: () => cleanEnum,
  cleanRegex: () => cleanRegex,
  clone: () => clone,
  createTransparentProxy: () => createTransparentProxy,
  defineLazy: () => defineLazy,
  esc: () => esc,
  escapeRegex: () => escapeRegex,
  extend: () => extend,
  finalizeIssue: () => finalizeIssue,
  floatSafeRemainder: () => floatSafeRemainder2,
  getElementAtPath: () => getElementAtPath,
  getEnumValues: () => getEnumValues,
  getLengthableOrigin: () => getLengthableOrigin,
  getParsedType: () => getParsedType2,
  getSizableOrigin: () => getSizableOrigin,
  isObject: () => isObject,
  isPlainObject: () => isPlainObject,
  issue: () => issue,
  joinValues: () => joinValues,
  jsonStringifyReplacer: () => jsonStringifyReplacer,
  merge: () => merge,
  normalizeParams: () => normalizeParams,
  nullish: () => nullish,
  numKeys: () => numKeys,
  omit: () => omit,
  optionalKeys: () => optionalKeys,
  partial: () => partial,
  pick: () => pick,
  prefixIssues: () => prefixIssues,
  primitiveTypes: () => primitiveTypes,
  promiseAllObject: () => promiseAllObject,
  propertyKeyTypes: () => propertyKeyTypes,
  randomString: () => randomString,
  required: () => required,
  stringifyPrimitive: () => stringifyPrimitive,
  unwrapMessage: () => unwrapMessage
});
function assertEqual(val) {
  return val;
}
function assertNotEqual(val) {
  return val;
}
function assertIs(_arg) {
}
function assertNever(_x) {
  throw new Error();
}
function assert(_) {
}
function getEnumValues(entries) {
  let numericValues = Object.values(entries).filter((v) => typeof v == "number");
  return Object.entries(entries).filter(([k, _]) => numericValues.indexOf(+k) === -1).map(([_, v]) => v);
}
function joinValues(array2, separator = "|") {
  return array2.map((val) => stringifyPrimitive(val)).join(separator);
}
function jsonStringifyReplacer(_, value) {
  return typeof value == "bigint" ? value.toString() : value;
}
function cached(getter) {
  return {
    get value() {
      {
        let value = getter();
        return Object.defineProperty(this, "value", { value }), value;
      }
      throw new Error("cached value already set");
    }
  };
}
function nullish(input) {
  return input == null;
}
function cleanRegex(source) {
  let start = source.startsWith("^") ? 1 : 0, end = source.endsWith("$") ? source.length - 1 : source.length;
  return source.slice(start, end);
}
function floatSafeRemainder2(val, step) {
  let valDecCount = (val.toString().split(".")[1] || "").length, stepDecCount = (step.toString().split(".")[1] || "").length, decCount = valDecCount > stepDecCount ? valDecCount : stepDecCount, valInt = Number.parseInt(val.toFixed(decCount).replace(".", "")), stepInt = Number.parseInt(step.toFixed(decCount).replace(".", ""));
  return valInt % stepInt / 10 ** decCount;
}
function defineLazy(object3, key, getter) {
  Object.defineProperty(object3, key, {
    get() {
      {
        let value = getter();
        return object3[key] = value, value;
      }
      throw new Error("cached value already set");
    },
    set(v) {
      Object.defineProperty(object3, key, {
        value: v
        // configurable: true,
      });
    },
    configurable: !0
  });
}
function assignProp(target, prop, value) {
  Object.defineProperty(target, prop, {
    value,
    writable: !0,
    enumerable: !0,
    configurable: !0
  });
}
function getElementAtPath(obj, path3) {
  return path3 ? path3.reduce((acc, key) => acc?.[key], obj) : obj;
}
function promiseAllObject(promisesObj) {
  let keys = Object.keys(promisesObj), promises = keys.map((key) => promisesObj[key]);
  return Promise.all(promises).then((results) => {
    let resolvedObj = {};
    for (let i = 0; i < keys.length; i++)
      resolvedObj[keys[i]] = results[i];
    return resolvedObj;
  });
}
function randomString(length = 10) {
  let chars = "abcdefghijklmnopqrstuvwxyz", str = "";
  for (let i = 0; i < length; i++)
    str += chars[Math.floor(Math.random() * chars.length)];
  return str;
}
function esc(str) {
  return JSON.stringify(str);
}
var captureStackTrace = Error.captureStackTrace ? Error.captureStackTrace : (..._args) => {
};
function isObject(data) {
  return typeof data == "object" && data !== null && !Array.isArray(data);
}
var allowsEval = cached(() => {
  if (typeof navigator < "u" && navigator?.userAgent?.includes("Cloudflare"))
    return !1;
  try {
    let F = Function;
    return new F(""), !0;
  } catch {
    return !1;
  }
});
function isPlainObject(o) {
  if (isObject(o) === !1)
    return !1;
  let ctor = o.constructor;
  if (ctor === void 0)
    return !0;
  let prot = ctor.prototype;
  return !(isObject(prot) === !1 || Object.prototype.hasOwnProperty.call(prot, "isPrototypeOf") === !1);
}
function numKeys(data) {
  let keyCount = 0;
  for (let key in data)
    Object.prototype.hasOwnProperty.call(data, key) && keyCount++;
  return keyCount;
}
var getParsedType2 = (data) => {
  let t = typeof data;
  switch (t) {
    case "undefined":
      return "undefined";
    case "string":
      return "string";
    case "number":
      return Number.isNaN(data) ? "nan" : "number";
    case "boolean":
      return "boolean";
    case "function":
      return "function";
    case "bigint":
      return "bigint";
    case "symbol":
      return "symbol";
    case "object":
      return Array.isArray(data) ? "array" : data === null ? "null" : data.then && typeof data.then == "function" && data.catch && typeof data.catch == "function" ? "promise" : typeof Map < "u" && data instanceof Map ? "map" : typeof Set < "u" && data instanceof Set ? "set" : typeof Date < "u" && data instanceof Date ? "date" : typeof File < "u" && data instanceof File ? "file" : "object";
    default:
      throw new Error(`Unknown data type: ${t}`);
  }
}, propertyKeyTypes = /* @__PURE__ */ new Set(["string", "number", "symbol"]), primitiveTypes = /* @__PURE__ */ new Set(["string", "number", "bigint", "boolean", "symbol", "undefined"]);
function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
function clone(inst, def, params) {
  let cl = new inst._zod.constr(def ?? inst._zod.def);
  return (!def || params?.parent) && (cl._zod.parent = inst), cl;
}
function normalizeParams(_params) {
  let params = _params;
  if (!params)
    return {};
  if (typeof params == "string")
    return { error: () => params };
  if (params?.message !== void 0) {
    if (params?.error !== void 0)
      throw new Error("Cannot specify both `message` and `error` params");
    params.error = params.message;
  }
  return delete params.message, typeof params.error == "string" ? { ...params, error: () => params.error } : params;
}
function createTransparentProxy(getter) {
  let target;
  return new Proxy({}, {
    get(_, prop, receiver) {
      return target ?? (target = getter()), Reflect.get(target, prop, receiver);
    },
    set(_, prop, value, receiver) {
      return target ?? (target = getter()), Reflect.set(target, prop, value, receiver);
    },
    has(_, prop) {
      return target ?? (target = getter()), Reflect.has(target, prop);
    },
    deleteProperty(_, prop) {
      return target ?? (target = getter()), Reflect.deleteProperty(target, prop);
    },
    ownKeys(_) {
      return target ?? (target = getter()), Reflect.ownKeys(target);
    },
    getOwnPropertyDescriptor(_, prop) {
      return target ?? (target = getter()), Reflect.getOwnPropertyDescriptor(target, prop);
    },
    defineProperty(_, prop, descriptor) {
      return target ?? (target = getter()), Reflect.defineProperty(target, prop, descriptor);
    }
  });
}
function stringifyPrimitive(value) {
  return typeof value == "bigint" ? value.toString() + "n" : typeof value == "string" ? `"${value}"` : `${value}`;
}
function optionalKeys(shape) {
  return Object.keys(shape).filter((k) => shape[k]._zod.optin === "optional" && shape[k]._zod.optout === "optional");
}
var NUMBER_FORMAT_RANGES = {
  safeint: [Number.MIN_SAFE_INTEGER, Number.MAX_SAFE_INTEGER],
  int32: [-2147483648, 2147483647],
  uint32: [0, 4294967295],
  float32: [-34028234663852886e22, 34028234663852886e22],
  float64: [-Number.MAX_VALUE, Number.MAX_VALUE]
}, BIGINT_FORMAT_RANGES = {
  int64: [/* @__PURE__ */ BigInt("-9223372036854775808"), /* @__PURE__ */ BigInt("9223372036854775807")],
  uint64: [/* @__PURE__ */ BigInt(0), /* @__PURE__ */ BigInt("18446744073709551615")]
};
function pick(schema, mask) {
  let newShape = {}, currDef = schema._zod.def;
  for (let key in mask) {
    if (!(key in currDef.shape))
      throw new Error(`Unrecognized key: "${key}"`);
    mask[key] && (newShape[key] = currDef.shape[key]);
  }
  return clone(schema, {
    ...schema._zod.def,
    shape: newShape,
    checks: []
  });
}
function omit(schema, mask) {
  let newShape = { ...schema._zod.def.shape }, currDef = schema._zod.def;
  for (let key in mask) {
    if (!(key in currDef.shape))
      throw new Error(`Unrecognized key: "${key}"`);
    mask[key] && delete newShape[key];
  }
  return clone(schema, {
    ...schema._zod.def,
    shape: newShape,
    checks: []
  });
}
function extend(schema, shape) {
  if (!isPlainObject(shape))
    throw new Error("Invalid input to extend: expected a plain object");
  let def = {
    ...schema._zod.def,
    get shape() {
      let _shape = { ...schema._zod.def.shape, ...shape };
      return assignProp(this, "shape", _shape), _shape;
    },
    checks: []
    // delete existing checks
  };
  return clone(schema, def);
}
function merge(a, b) {
  return clone(a, {
    ...a._zod.def,
    get shape() {
      let _shape = { ...a._zod.def.shape, ...b._zod.def.shape };
      return assignProp(this, "shape", _shape), _shape;
    },
    catchall: b._zod.def.catchall,
    checks: []
    // delete existing checks
  });
}
function partial(Class2, schema, mask) {
  let oldShape = schema._zod.def.shape, shape = { ...oldShape };
  if (mask)
    for (let key in mask) {
      if (!(key in oldShape))
        throw new Error(`Unrecognized key: "${key}"`);
      mask[key] && (shape[key] = Class2 ? new Class2({
        type: "optional",
        innerType: oldShape[key]
      }) : oldShape[key]);
    }
  else
    for (let key in oldShape)
      shape[key] = Class2 ? new Class2({
        type: "optional",
        innerType: oldShape[key]
      }) : oldShape[key];
  return clone(schema, {
    ...schema._zod.def,
    shape,
    checks: []
  });
}
function required(Class2, schema, mask) {
  let oldShape = schema._zod.def.shape, shape = { ...oldShape };
  if (mask)
    for (let key in mask) {
      if (!(key in shape))
        throw new Error(`Unrecognized key: "${key}"`);
      mask[key] && (shape[key] = new Class2({
        type: "nonoptional",
        innerType: oldShape[key]
      }));
    }
  else
    for (let key in oldShape)
      shape[key] = new Class2({
        type: "nonoptional",
        innerType: oldShape[key]
      });
  return clone(schema, {
    ...schema._zod.def,
    shape,
    // optional: [],
    checks: []
  });
}
function aborted(x, startIndex = 0) {
  for (let i = startIndex; i < x.issues.length; i++)
    if (x.issues[i]?.continue !== !0)
      return !0;
  return !1;
}
function prefixIssues(path3, issues) {
  return issues.map((iss) => {
    var _a;
    return (_a = iss).path ?? (_a.path = []), iss.path.unshift(path3), iss;
  });
}
function unwrapMessage(message) {
  return typeof message == "string" ? message : message?.message;
}
function finalizeIssue(iss, ctx, config2) {
  let full = { ...iss, path: iss.path ?? [] };
  if (!iss.message) {
    let message = unwrapMessage(iss.inst?._zod.def?.error?.(iss)) ?? unwrapMessage(ctx?.error?.(iss)) ?? unwrapMessage(config2.customError?.(iss)) ?? unwrapMessage(config2.localeError?.(iss)) ?? "Invalid input";
    full.message = message;
  }
  return delete full.inst, delete full.continue, ctx?.reportInput || delete full.input, full;
}
function getSizableOrigin(input) {
  return input instanceof Set ? "set" : input instanceof Map ? "map" : input instanceof File ? "file" : "unknown";
}
function getLengthableOrigin(input) {
  return Array.isArray(input) ? "array" : typeof input == "string" ? "string" : "unknown";
}
function issue(...args) {
  let [iss, input, inst] = args;
  return typeof iss == "string" ? {
    message: iss,
    code: "custom",
    input,
    inst
  } : { ...iss };
}
function cleanEnum(obj) {
  return Object.entries(obj).filter(([k, _]) => Number.isNaN(Number.parseInt(k, 10))).map((el) => el[1]);
}
var Class = class {
  constructor(..._args) {
  }
};

// node_modules/zod/v4/core/errors.js
var initializer = (inst, def) => {
  inst.name = "$ZodError", Object.defineProperty(inst, "_zod", {
    value: inst._zod,
    enumerable: !1
  }), Object.defineProperty(inst, "issues", {
    value: def,
    enumerable: !1
  }), Object.defineProperty(inst, "message", {
    get() {
      return JSON.stringify(def, jsonStringifyReplacer, 2);
    },
    enumerable: !0
    // configurable: false,
  }), Object.defineProperty(inst, "toString", {
    value: () => inst.message,
    enumerable: !1
  });
}, $ZodError = $constructor("$ZodError", initializer), $ZodRealError = $constructor("$ZodError", initializer, { Parent: Error });
function flattenError(error2, mapper = (issue2) => issue2.message) {
  let fieldErrors = {}, formErrors = [];
  for (let sub of error2.issues)
    sub.path.length > 0 ? (fieldErrors[sub.path[0]] = fieldErrors[sub.path[0]] || [], fieldErrors[sub.path[0]].push(mapper(sub))) : formErrors.push(mapper(sub));
  return { formErrors, fieldErrors };
}
function formatError(error2, _mapper) {
  let mapper = _mapper || function(issue2) {
    return issue2.message;
  }, fieldErrors = { _errors: [] }, processError = (error3) => {
    for (let issue2 of error3.issues)
      if (issue2.code === "invalid_union" && issue2.errors.length)
        issue2.errors.map((issues) => processError({ issues }));
      else if (issue2.code === "invalid_key")
        processError({ issues: issue2.issues });
      else if (issue2.code === "invalid_element")
        processError({ issues: issue2.issues });
      else if (issue2.path.length === 0)
        fieldErrors._errors.push(mapper(issue2));
      else {
        let curr = fieldErrors, i = 0;
        for (; i < issue2.path.length; ) {
          let el = issue2.path[i];
          i === issue2.path.length - 1 ? (curr[el] = curr[el] || { _errors: [] }, curr[el]._errors.push(mapper(issue2))) : curr[el] = curr[el] || { _errors: [] }, curr = curr[el], i++;
        }
      }
  };
  return processError(error2), fieldErrors;
}

// node_modules/zod/v4/core/parse.js
var _parse = (_Err) => (schema, value, _ctx, _params) => {
  let ctx = _ctx ? Object.assign(_ctx, { async: !1 }) : { async: !1 }, result = schema._zod.run({ value, issues: [] }, ctx);
  if (result instanceof Promise)
    throw new $ZodAsyncError();
  if (result.issues.length) {
    let e = new (_params?.Err ?? _Err)(result.issues.map((iss) => finalizeIssue(iss, ctx, config())));
    throw captureStackTrace(e, _params?.callee), e;
  }
  return result.value;
}, parse = /* @__PURE__ */ _parse($ZodRealError), _parseAsync = (_Err) => async (schema, value, _ctx, params) => {
  let ctx = _ctx ? Object.assign(_ctx, { async: !0 }) : { async: !0 }, result = schema._zod.run({ value, issues: [] }, ctx);
  if (result instanceof Promise && (result = await result), result.issues.length) {
    let e = new (params?.Err ?? _Err)(result.issues.map((iss) => finalizeIssue(iss, ctx, config())));
    throw captureStackTrace(e, params?.callee), e;
  }
  return result.value;
}, parseAsync = /* @__PURE__ */ _parseAsync($ZodRealError), _safeParse = (_Err) => (schema, value, _ctx) => {
  let ctx = _ctx ? { ..._ctx, async: !1 } : { async: !1 }, result = schema._zod.run({ value, issues: [] }, ctx);
  if (result instanceof Promise)
    throw new $ZodAsyncError();
  return result.issues.length ? {
    success: !1,
    error: new (_Err ?? $ZodError)(result.issues.map((iss) => finalizeIssue(iss, ctx, config())))
  } : { success: !0, data: result.value };
}, safeParse = /* @__PURE__ */ _safeParse($ZodRealError), _safeParseAsync = (_Err) => async (schema, value, _ctx) => {
  let ctx = _ctx ? Object.assign(_ctx, { async: !0 }) : { async: !0 }, result = schema._zod.run({ value, issues: [] }, ctx);
  return result instanceof Promise && (result = await result), result.issues.length ? {
    success: !1,
    error: new _Err(result.issues.map((iss) => finalizeIssue(iss, ctx, config())))
  } : { success: !0, data: result.value };
}, safeParseAsync = /* @__PURE__ */ _safeParseAsync($ZodRealError);

// node_modules/zod/v4/core/regexes.js
var cuid = /^[cC][^\s-]{8,}$/, cuid2 = /^[0-9a-z]+$/, ulid = /^[0-9A-HJKMNP-TV-Za-hjkmnp-tv-z]{26}$/, xid = /^[0-9a-vA-V]{20}$/, ksuid = /^[A-Za-z0-9]{27}$/, nanoid = /^[a-zA-Z0-9_-]{21}$/, duration = /^P(?:(\d+W)|(?!.*W)(?=\d|T\d)(\d+Y)?(\d+M)?(\d+D)?(T(?=\d)(\d+H)?(\d+M)?(\d+([.,]\d+)?S)?)?)$/;
var guid = /^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/, uuid = (version2) => version2 ? new RegExp(`^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-${version2}[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})$`) : /^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000)$/;
var email = /^(?!\.)(?!.*\.\.)([A-Za-z0-9_'+\-\.]*)[A-Za-z0-9_+-]@([A-Za-z0-9][A-Za-z0-9\-]*\.)+[A-Za-z]{2,}$/;
var _emoji = "^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$";
function emoji() {
  return new RegExp(_emoji, "u");
}
var ipv4 = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/, ipv6 = /^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|::|([0-9a-fA-F]{1,4})?::([0-9a-fA-F]{1,4}:?){0,6})$/, cidrv4 = /^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\/([0-9]|[1-2][0-9]|3[0-2])$/, cidrv6 = /^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|::|([0-9a-fA-F]{1,4})?::([0-9a-fA-F]{1,4}:?){0,6})\/(12[0-8]|1[01][0-9]|[1-9]?[0-9])$/, base64 = /^$|^(?:[0-9a-zA-Z+/]{4})*(?:(?:[0-9a-zA-Z+/]{2}==)|(?:[0-9a-zA-Z+/]{3}=))?$/, base64url = /^[A-Za-z0-9_-]*$/, hostname = /^([a-zA-Z0-9-]+\.)*[a-zA-Z0-9-]+$/;
var e164 = /^\+(?:[0-9]){6,14}[0-9]$/, dateSource = "(?:(?:\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|\\d{4}-(?:(?:0[13578]|1[02])-(?:0[1-9]|[12]\\d|3[01])|(?:0[469]|11)-(?:0[1-9]|[12]\\d|30)|(?:02)-(?:0[1-9]|1\\d|2[0-8])))", date = /* @__PURE__ */ new RegExp(`^${dateSource}$`);
function timeSource(args) {
  let hhmm = "(?:[01]\\d|2[0-3]):[0-5]\\d";
  return typeof args.precision == "number" ? args.precision === -1 ? `${hhmm}` : args.precision === 0 ? `${hhmm}:[0-5]\\d` : `${hhmm}:[0-5]\\d\\.\\d{${args.precision}}` : `${hhmm}(?::[0-5]\\d(?:\\.\\d+)?)?`;
}
function time(args) {
  return new RegExp(`^${timeSource(args)}$`);
}
function datetime(args) {
  let time3 = timeSource({ precision: args.precision }), opts = ["Z"];
  args.local && opts.push(""), args.offset && opts.push("([+-]\\d{2}:\\d{2})");
  let timeRegex2 = `${time3}(?:${opts.join("|")})`;
  return new RegExp(`^${dateSource}T(?:${timeRegex2})$`);
}
var string = (params) => {
  let regex = params ? `[\\s\\S]{${params?.minimum ?? 0},${params?.maximum ?? ""}}` : "[\\s\\S]*";
  return new RegExp(`^${regex}$`);
};
var integer = /^\d+$/, number = /^-?\d+(?:\.\d+)?/i, boolean = /true|false/i, _null = /null/i;
var lowercase = /^[^A-Z]*$/, uppercase = /^[^a-z]*$/;

// node_modules/zod/v4/core/checks.js
var $ZodCheck = /* @__PURE__ */ $constructor("$ZodCheck", (inst, def) => {
  var _a;
  inst._zod ?? (inst._zod = {}), inst._zod.def = def, (_a = inst._zod).onattach ?? (_a.onattach = []);
}), numericOriginMap = {
  number: "number",
  bigint: "bigint",
  object: "date"
}, $ZodCheckLessThan = /* @__PURE__ */ $constructor("$ZodCheckLessThan", (inst, def) => {
  $ZodCheck.init(inst, def);
  let origin = numericOriginMap[typeof def.value];
  inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag, curr = (def.inclusive ? bag.maximum : bag.exclusiveMaximum) ?? Number.POSITIVE_INFINITY;
    def.value < curr && (def.inclusive ? bag.maximum = def.value : bag.exclusiveMaximum = def.value);
  }), inst._zod.check = (payload) => {
    (def.inclusive ? payload.value <= def.value : payload.value < def.value) || payload.issues.push({
      origin,
      code: "too_big",
      maximum: def.value,
      input: payload.value,
      inclusive: def.inclusive,
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckGreaterThan = /* @__PURE__ */ $constructor("$ZodCheckGreaterThan", (inst, def) => {
  $ZodCheck.init(inst, def);
  let origin = numericOriginMap[typeof def.value];
  inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag, curr = (def.inclusive ? bag.minimum : bag.exclusiveMinimum) ?? Number.NEGATIVE_INFINITY;
    def.value > curr && (def.inclusive ? bag.minimum = def.value : bag.exclusiveMinimum = def.value);
  }), inst._zod.check = (payload) => {
    (def.inclusive ? payload.value >= def.value : payload.value > def.value) || payload.issues.push({
      origin,
      code: "too_small",
      minimum: def.value,
      input: payload.value,
      inclusive: def.inclusive,
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckMultipleOf = /* @__PURE__ */ $constructor("$ZodCheckMultipleOf", (inst, def) => {
  $ZodCheck.init(inst, def), inst._zod.onattach.push((inst2) => {
    var _a;
    (_a = inst2._zod.bag).multipleOf ?? (_a.multipleOf = def.value);
  }), inst._zod.check = (payload) => {
    if (typeof payload.value != typeof def.value)
      throw new Error("Cannot mix number and bigint in multiple_of check.");
    (typeof payload.value == "bigint" ? payload.value % def.value === BigInt(0) : floatSafeRemainder2(payload.value, def.value) === 0) || payload.issues.push({
      origin: typeof payload.value,
      code: "not_multiple_of",
      divisor: def.value,
      input: payload.value,
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckNumberFormat = /* @__PURE__ */ $constructor("$ZodCheckNumberFormat", (inst, def) => {
  $ZodCheck.init(inst, def), def.format = def.format || "float64";
  let isInt = def.format?.includes("int"), origin = isInt ? "int" : "number", [minimum, maximum] = NUMBER_FORMAT_RANGES[def.format];
  inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag;
    bag.format = def.format, bag.minimum = minimum, bag.maximum = maximum, isInt && (bag.pattern = integer);
  }), inst._zod.check = (payload) => {
    let input = payload.value;
    if (isInt) {
      if (!Number.isInteger(input)) {
        payload.issues.push({
          expected: origin,
          format: def.format,
          code: "invalid_type",
          input,
          inst
        });
        return;
      }
      if (!Number.isSafeInteger(input)) {
        input > 0 ? payload.issues.push({
          input,
          code: "too_big",
          maximum: Number.MAX_SAFE_INTEGER,
          note: "Integers must be within the safe integer range.",
          inst,
          origin,
          continue: !def.abort
        }) : payload.issues.push({
          input,
          code: "too_small",
          minimum: Number.MIN_SAFE_INTEGER,
          note: "Integers must be within the safe integer range.",
          inst,
          origin,
          continue: !def.abort
        });
        return;
      }
    }
    input < minimum && payload.issues.push({
      origin: "number",
      input,
      code: "too_small",
      minimum,
      inclusive: !0,
      inst,
      continue: !def.abort
    }), input > maximum && payload.issues.push({
      origin: "number",
      input,
      code: "too_big",
      maximum,
      inst
    });
  };
});
var $ZodCheckMaxLength = /* @__PURE__ */ $constructor("$ZodCheckMaxLength", (inst, def) => {
  var _a;
  $ZodCheck.init(inst, def), (_a = inst._zod.def).when ?? (_a.when = (payload) => {
    let val = payload.value;
    return !nullish(val) && val.length !== void 0;
  }), inst._zod.onattach.push((inst2) => {
    let curr = inst2._zod.bag.maximum ?? Number.POSITIVE_INFINITY;
    def.maximum < curr && (inst2._zod.bag.maximum = def.maximum);
  }), inst._zod.check = (payload) => {
    let input = payload.value;
    if (input.length <= def.maximum)
      return;
    let origin = getLengthableOrigin(input);
    payload.issues.push({
      origin,
      code: "too_big",
      maximum: def.maximum,
      inclusive: !0,
      input,
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckMinLength = /* @__PURE__ */ $constructor("$ZodCheckMinLength", (inst, def) => {
  var _a;
  $ZodCheck.init(inst, def), (_a = inst._zod.def).when ?? (_a.when = (payload) => {
    let val = payload.value;
    return !nullish(val) && val.length !== void 0;
  }), inst._zod.onattach.push((inst2) => {
    let curr = inst2._zod.bag.minimum ?? Number.NEGATIVE_INFINITY;
    def.minimum > curr && (inst2._zod.bag.minimum = def.minimum);
  }), inst._zod.check = (payload) => {
    let input = payload.value;
    if (input.length >= def.minimum)
      return;
    let origin = getLengthableOrigin(input);
    payload.issues.push({
      origin,
      code: "too_small",
      minimum: def.minimum,
      inclusive: !0,
      input,
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckLengthEquals = /* @__PURE__ */ $constructor("$ZodCheckLengthEquals", (inst, def) => {
  var _a;
  $ZodCheck.init(inst, def), (_a = inst._zod.def).when ?? (_a.when = (payload) => {
    let val = payload.value;
    return !nullish(val) && val.length !== void 0;
  }), inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag;
    bag.minimum = def.length, bag.maximum = def.length, bag.length = def.length;
  }), inst._zod.check = (payload) => {
    let input = payload.value, length = input.length;
    if (length === def.length)
      return;
    let origin = getLengthableOrigin(input), tooBig = length > def.length;
    payload.issues.push({
      origin,
      ...tooBig ? { code: "too_big", maximum: def.length } : { code: "too_small", minimum: def.length },
      inclusive: !0,
      exact: !0,
      input: payload.value,
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckStringFormat = /* @__PURE__ */ $constructor("$ZodCheckStringFormat", (inst, def) => {
  var _a, _b;
  $ZodCheck.init(inst, def), inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag;
    bag.format = def.format, def.pattern && (bag.patterns ?? (bag.patterns = /* @__PURE__ */ new Set()), bag.patterns.add(def.pattern));
  }), def.pattern ? (_a = inst._zod).check ?? (_a.check = (payload) => {
    def.pattern.lastIndex = 0, !def.pattern.test(payload.value) && payload.issues.push({
      origin: "string",
      code: "invalid_format",
      format: def.format,
      input: payload.value,
      ...def.pattern ? { pattern: def.pattern.toString() } : {},
      inst,
      continue: !def.abort
    });
  }) : (_b = inst._zod).check ?? (_b.check = () => {
  });
}), $ZodCheckRegex = /* @__PURE__ */ $constructor("$ZodCheckRegex", (inst, def) => {
  $ZodCheckStringFormat.init(inst, def), inst._zod.check = (payload) => {
    def.pattern.lastIndex = 0, !def.pattern.test(payload.value) && payload.issues.push({
      origin: "string",
      code: "invalid_format",
      format: "regex",
      input: payload.value,
      pattern: def.pattern.toString(),
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckLowerCase = /* @__PURE__ */ $constructor("$ZodCheckLowerCase", (inst, def) => {
  def.pattern ?? (def.pattern = lowercase), $ZodCheckStringFormat.init(inst, def);
}), $ZodCheckUpperCase = /* @__PURE__ */ $constructor("$ZodCheckUpperCase", (inst, def) => {
  def.pattern ?? (def.pattern = uppercase), $ZodCheckStringFormat.init(inst, def);
}), $ZodCheckIncludes = /* @__PURE__ */ $constructor("$ZodCheckIncludes", (inst, def) => {
  $ZodCheck.init(inst, def);
  let escapedRegex = escapeRegex(def.includes), pattern = new RegExp(typeof def.position == "number" ? `^.{${def.position}}${escapedRegex}` : escapedRegex);
  def.pattern = pattern, inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag;
    bag.patterns ?? (bag.patterns = /* @__PURE__ */ new Set()), bag.patterns.add(pattern);
  }), inst._zod.check = (payload) => {
    payload.value.includes(def.includes, def.position) || payload.issues.push({
      origin: "string",
      code: "invalid_format",
      format: "includes",
      includes: def.includes,
      input: payload.value,
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckStartsWith = /* @__PURE__ */ $constructor("$ZodCheckStartsWith", (inst, def) => {
  $ZodCheck.init(inst, def);
  let pattern = new RegExp(`^${escapeRegex(def.prefix)}.*`);
  def.pattern ?? (def.pattern = pattern), inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag;
    bag.patterns ?? (bag.patterns = /* @__PURE__ */ new Set()), bag.patterns.add(pattern);
  }), inst._zod.check = (payload) => {
    payload.value.startsWith(def.prefix) || payload.issues.push({
      origin: "string",
      code: "invalid_format",
      format: "starts_with",
      prefix: def.prefix,
      input: payload.value,
      inst,
      continue: !def.abort
    });
  };
}), $ZodCheckEndsWith = /* @__PURE__ */ $constructor("$ZodCheckEndsWith", (inst, def) => {
  $ZodCheck.init(inst, def);
  let pattern = new RegExp(`.*${escapeRegex(def.suffix)}$`);
  def.pattern ?? (def.pattern = pattern), inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag;
    bag.patterns ?? (bag.patterns = /* @__PURE__ */ new Set()), bag.patterns.add(pattern);
  }), inst._zod.check = (payload) => {
    payload.value.endsWith(def.suffix) || payload.issues.push({
      origin: "string",
      code: "invalid_format",
      format: "ends_with",
      suffix: def.suffix,
      input: payload.value,
      inst,
      continue: !def.abort
    });
  };
});
var $ZodCheckOverwrite = /* @__PURE__ */ $constructor("$ZodCheckOverwrite", (inst, def) => {
  $ZodCheck.init(inst, def), inst._zod.check = (payload) => {
    payload.value = def.tx(payload.value);
  };
});

// node_modules/zod/v4/core/doc.js
var Doc = class {
  constructor(args = []) {
    this.content = [], this.indent = 0, this && (this.args = args);
  }
  indented(fn) {
    this.indent += 1, fn(this), this.indent -= 1;
  }
  write(arg) {
    if (typeof arg == "function") {
      arg(this, { execution: "sync" }), arg(this, { execution: "async" });
      return;
    }
    let lines = arg.split(`
`).filter((x) => x), minIndent = Math.min(...lines.map((x) => x.length - x.trimStart().length)), dedented = lines.map((x) => x.slice(minIndent)).map((x) => " ".repeat(this.indent * 2) + x);
    for (let line of dedented)
      this.content.push(line);
  }
  compile() {
    let F = Function, args = this?.args, lines = [...(this?.content ?? [""]).map((x) => `  ${x}`)];
    return new F(...args, lines.join(`
`));
  }
};

// node_modules/zod/v4/core/versions.js
var version = {
  major: 4,
  minor: 0,
  patch: 0
};

// node_modules/zod/v4/core/schemas.js
var $ZodType = /* @__PURE__ */ $constructor("$ZodType", (inst, def) => {
  var _a;
  inst ?? (inst = {}), inst._zod.def = def, inst._zod.bag = inst._zod.bag || {}, inst._zod.version = version;
  let checks = [...inst._zod.def.checks ?? []];
  inst._zod.traits.has("$ZodCheck") && checks.unshift(inst);
  for (let ch of checks)
    for (let fn of ch._zod.onattach)
      fn(inst);
  if (checks.length === 0)
    (_a = inst._zod).deferred ?? (_a.deferred = []), inst._zod.deferred?.push(() => {
      inst._zod.run = inst._zod.parse;
    });
  else {
    let runChecks = (payload, checks2, ctx) => {
      let isAborted2 = aborted(payload), asyncResult;
      for (let ch of checks2) {
        if (ch._zod.def.when) {
          if (!ch._zod.def.when(payload))
            continue;
        } else if (isAborted2)
          continue;
        let currLen = payload.issues.length, _ = ch._zod.check(payload);
        if (_ instanceof Promise && ctx?.async === !1)
          throw new $ZodAsyncError();
        if (asyncResult || _ instanceof Promise)
          asyncResult = (asyncResult ?? Promise.resolve()).then(async () => {
            await _, payload.issues.length !== currLen && (isAborted2 || (isAborted2 = aborted(payload, currLen)));
          });
        else {
          if (payload.issues.length === currLen)
            continue;
          isAborted2 || (isAborted2 = aborted(payload, currLen));
        }
      }
      return asyncResult ? asyncResult.then(() => payload) : payload;
    };
    inst._zod.run = (payload, ctx) => {
      let result = inst._zod.parse(payload, ctx);
      if (result instanceof Promise) {
        if (ctx.async === !1)
          throw new $ZodAsyncError();
        return result.then((result2) => runChecks(result2, checks, ctx));
      }
      return runChecks(result, checks, ctx);
    };
  }
  inst["~standard"] = {
    validate: (value) => {
      try {
        let r = safeParse(inst, value);
        return r.success ? { value: r.data } : { issues: r.error?.issues };
      } catch {
        return safeParseAsync(inst, value).then((r) => r.success ? { value: r.data } : { issues: r.error?.issues });
      }
    },
    vendor: "zod",
    version: 1
  };
}), $ZodString = /* @__PURE__ */ $constructor("$ZodString", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.pattern = [...inst?._zod.bag?.patterns ?? []].pop() ?? string(inst._zod.bag), inst._zod.parse = (payload, _) => {
    if (def.coerce)
      try {
        payload.value = String(payload.value);
      } catch {
      }
    return typeof payload.value == "string" || payload.issues.push({
      expected: "string",
      code: "invalid_type",
      input: payload.value,
      inst
    }), payload;
  };
}), $ZodStringFormat = /* @__PURE__ */ $constructor("$ZodStringFormat", (inst, def) => {
  $ZodCheckStringFormat.init(inst, def), $ZodString.init(inst, def);
}), $ZodGUID = /* @__PURE__ */ $constructor("$ZodGUID", (inst, def) => {
  def.pattern ?? (def.pattern = guid), $ZodStringFormat.init(inst, def);
}), $ZodUUID = /* @__PURE__ */ $constructor("$ZodUUID", (inst, def) => {
  if (def.version) {
    let v = {
      v1: 1,
      v2: 2,
      v3: 3,
      v4: 4,
      v5: 5,
      v6: 6,
      v7: 7,
      v8: 8
    }[def.version];
    if (v === void 0)
      throw new Error(`Invalid UUID version: "${def.version}"`);
    def.pattern ?? (def.pattern = uuid(v));
  } else
    def.pattern ?? (def.pattern = uuid());
  $ZodStringFormat.init(inst, def);
}), $ZodEmail = /* @__PURE__ */ $constructor("$ZodEmail", (inst, def) => {
  def.pattern ?? (def.pattern = email), $ZodStringFormat.init(inst, def);
}), $ZodURL = /* @__PURE__ */ $constructor("$ZodURL", (inst, def) => {
  $ZodStringFormat.init(inst, def), inst._zod.check = (payload) => {
    try {
      let orig = payload.value, url = new URL(orig), href = url.href;
      def.hostname && (def.hostname.lastIndex = 0, def.hostname.test(url.hostname) || payload.issues.push({
        code: "invalid_format",
        format: "url",
        note: "Invalid hostname",
        pattern: hostname.source,
        input: payload.value,
        inst,
        continue: !def.abort
      })), def.protocol && (def.protocol.lastIndex = 0, def.protocol.test(url.protocol.endsWith(":") ? url.protocol.slice(0, -1) : url.protocol) || payload.issues.push({
        code: "invalid_format",
        format: "url",
        note: "Invalid protocol",
        pattern: def.protocol.source,
        input: payload.value,
        inst,
        continue: !def.abort
      })), !orig.endsWith("/") && href.endsWith("/") ? payload.value = href.slice(0, -1) : payload.value = href;
      return;
    } catch {
      payload.issues.push({
        code: "invalid_format",
        format: "url",
        input: payload.value,
        inst,
        continue: !def.abort
      });
    }
  };
}), $ZodEmoji = /* @__PURE__ */ $constructor("$ZodEmoji", (inst, def) => {
  def.pattern ?? (def.pattern = emoji()), $ZodStringFormat.init(inst, def);
}), $ZodNanoID = /* @__PURE__ */ $constructor("$ZodNanoID", (inst, def) => {
  def.pattern ?? (def.pattern = nanoid), $ZodStringFormat.init(inst, def);
}), $ZodCUID = /* @__PURE__ */ $constructor("$ZodCUID", (inst, def) => {
  def.pattern ?? (def.pattern = cuid), $ZodStringFormat.init(inst, def);
}), $ZodCUID2 = /* @__PURE__ */ $constructor("$ZodCUID2", (inst, def) => {
  def.pattern ?? (def.pattern = cuid2), $ZodStringFormat.init(inst, def);
}), $ZodULID = /* @__PURE__ */ $constructor("$ZodULID", (inst, def) => {
  def.pattern ?? (def.pattern = ulid), $ZodStringFormat.init(inst, def);
}), $ZodXID = /* @__PURE__ */ $constructor("$ZodXID", (inst, def) => {
  def.pattern ?? (def.pattern = xid), $ZodStringFormat.init(inst, def);
}), $ZodKSUID = /* @__PURE__ */ $constructor("$ZodKSUID", (inst, def) => {
  def.pattern ?? (def.pattern = ksuid), $ZodStringFormat.init(inst, def);
}), $ZodISODateTime = /* @__PURE__ */ $constructor("$ZodISODateTime", (inst, def) => {
  def.pattern ?? (def.pattern = datetime(def)), $ZodStringFormat.init(inst, def);
}), $ZodISODate = /* @__PURE__ */ $constructor("$ZodISODate", (inst, def) => {
  def.pattern ?? (def.pattern = date), $ZodStringFormat.init(inst, def);
}), $ZodISOTime = /* @__PURE__ */ $constructor("$ZodISOTime", (inst, def) => {
  def.pattern ?? (def.pattern = time(def)), $ZodStringFormat.init(inst, def);
}), $ZodISODuration = /* @__PURE__ */ $constructor("$ZodISODuration", (inst, def) => {
  def.pattern ?? (def.pattern = duration), $ZodStringFormat.init(inst, def);
}), $ZodIPv4 = /* @__PURE__ */ $constructor("$ZodIPv4", (inst, def) => {
  def.pattern ?? (def.pattern = ipv4), $ZodStringFormat.init(inst, def), inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag;
    bag.format = "ipv4";
  });
}), $ZodIPv6 = /* @__PURE__ */ $constructor("$ZodIPv6", (inst, def) => {
  def.pattern ?? (def.pattern = ipv6), $ZodStringFormat.init(inst, def), inst._zod.onattach.push((inst2) => {
    let bag = inst2._zod.bag;
    bag.format = "ipv6";
  }), inst._zod.check = (payload) => {
    try {
      new URL(`http://[${payload.value}]`);
    } catch {
      payload.issues.push({
        code: "invalid_format",
        format: "ipv6",
        input: payload.value,
        inst,
        continue: !def.abort
      });
    }
  };
}), $ZodCIDRv4 = /* @__PURE__ */ $constructor("$ZodCIDRv4", (inst, def) => {
  def.pattern ?? (def.pattern = cidrv4), $ZodStringFormat.init(inst, def);
}), $ZodCIDRv6 = /* @__PURE__ */ $constructor("$ZodCIDRv6", (inst, def) => {
  def.pattern ?? (def.pattern = cidrv6), $ZodStringFormat.init(inst, def), inst._zod.check = (payload) => {
    let [address, prefix] = payload.value.split("/");
    try {
      if (!prefix)
        throw new Error();
      let prefixNum = Number(prefix);
      if (`${prefixNum}` !== prefix)
        throw new Error();
      if (prefixNum < 0 || prefixNum > 128)
        throw new Error();
      new URL(`http://[${address}]`);
    } catch {
      payload.issues.push({
        code: "invalid_format",
        format: "cidrv6",
        input: payload.value,
        inst,
        continue: !def.abort
      });
    }
  };
});
function isValidBase64(data) {
  if (data === "")
    return !0;
  if (data.length % 4 !== 0)
    return !1;
  try {
    return atob(data), !0;
  } catch {
    return !1;
  }
}
var $ZodBase64 = /* @__PURE__ */ $constructor("$ZodBase64", (inst, def) => {
  def.pattern ?? (def.pattern = base64), $ZodStringFormat.init(inst, def), inst._zod.onattach.push((inst2) => {
    inst2._zod.bag.contentEncoding = "base64";
  }), inst._zod.check = (payload) => {
    isValidBase64(payload.value) || payload.issues.push({
      code: "invalid_format",
      format: "base64",
      input: payload.value,
      inst,
      continue: !def.abort
    });
  };
});
function isValidBase64URL(data) {
  if (!base64url.test(data))
    return !1;
  let base642 = data.replace(/[-_]/g, (c) => c === "-" ? "+" : "/"), padded = base642.padEnd(Math.ceil(base642.length / 4) * 4, "=");
  return isValidBase64(padded);
}
var $ZodBase64URL = /* @__PURE__ */ $constructor("$ZodBase64URL", (inst, def) => {
  def.pattern ?? (def.pattern = base64url), $ZodStringFormat.init(inst, def), inst._zod.onattach.push((inst2) => {
    inst2._zod.bag.contentEncoding = "base64url";
  }), inst._zod.check = (payload) => {
    isValidBase64URL(payload.value) || payload.issues.push({
      code: "invalid_format",
      format: "base64url",
      input: payload.value,
      inst,
      continue: !def.abort
    });
  };
}), $ZodE164 = /* @__PURE__ */ $constructor("$ZodE164", (inst, def) => {
  def.pattern ?? (def.pattern = e164), $ZodStringFormat.init(inst, def);
});
function isValidJWT2(token, algorithm = null) {
  try {
    let tokensParts = token.split(".");
    if (tokensParts.length !== 3)
      return !1;
    let [header] = tokensParts;
    if (!header)
      return !1;
    let parsedHeader = JSON.parse(atob(header));
    return !("typ" in parsedHeader && parsedHeader?.typ !== "JWT" || !parsedHeader.alg || algorithm && (!("alg" in parsedHeader) || parsedHeader.alg !== algorithm));
  } catch {
    return !1;
  }
}
var $ZodJWT = /* @__PURE__ */ $constructor("$ZodJWT", (inst, def) => {
  $ZodStringFormat.init(inst, def), inst._zod.check = (payload) => {
    isValidJWT2(payload.value, def.alg) || payload.issues.push({
      code: "invalid_format",
      format: "jwt",
      input: payload.value,
      inst,
      continue: !def.abort
    });
  };
});
var $ZodNumber = /* @__PURE__ */ $constructor("$ZodNumber", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.pattern = inst._zod.bag.pattern ?? number, inst._zod.parse = (payload, _ctx) => {
    if (def.coerce)
      try {
        payload.value = Number(payload.value);
      } catch {
      }
    let input = payload.value;
    if (typeof input == "number" && !Number.isNaN(input) && Number.isFinite(input))
      return payload;
    let received = typeof input == "number" ? Number.isNaN(input) ? "NaN" : Number.isFinite(input) ? void 0 : "Infinity" : void 0;
    return payload.issues.push({
      expected: "number",
      code: "invalid_type",
      input,
      inst,
      ...received ? { received } : {}
    }), payload;
  };
}), $ZodNumberFormat = /* @__PURE__ */ $constructor("$ZodNumber", (inst, def) => {
  $ZodCheckNumberFormat.init(inst, def), $ZodNumber.init(inst, def);
}), $ZodBoolean = /* @__PURE__ */ $constructor("$ZodBoolean", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.pattern = boolean, inst._zod.parse = (payload, _ctx) => {
    if (def.coerce)
      try {
        payload.value = !!payload.value;
      } catch {
      }
    let input = payload.value;
    return typeof input == "boolean" || payload.issues.push({
      expected: "boolean",
      code: "invalid_type",
      input,
      inst
    }), payload;
  };
});
var $ZodNull = /* @__PURE__ */ $constructor("$ZodNull", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.pattern = _null, inst._zod.values = /* @__PURE__ */ new Set([null]), inst._zod.parse = (payload, _ctx) => {
    let input = payload.value;
    return input === null || payload.issues.push({
      expected: "null",
      code: "invalid_type",
      input,
      inst
    }), payload;
  };
});
var $ZodUnknown = /* @__PURE__ */ $constructor("$ZodUnknown", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.parse = (payload) => payload;
}), $ZodNever = /* @__PURE__ */ $constructor("$ZodNever", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.parse = (payload, _ctx) => (payload.issues.push({
    expected: "never",
    code: "invalid_type",
    input: payload.value,
    inst
  }), payload);
});
function handleArrayResult(result, final, index) {
  result.issues.length && final.issues.push(...prefixIssues(index, result.issues)), final.value[index] = result.value;
}
var $ZodArray = /* @__PURE__ */ $constructor("$ZodArray", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.parse = (payload, ctx) => {
    let input = payload.value;
    if (!Array.isArray(input))
      return payload.issues.push({
        expected: "array",
        code: "invalid_type",
        input,
        inst
      }), payload;
    payload.value = Array(input.length);
    let proms = [];
    for (let i = 0; i < input.length; i++) {
      let item = input[i], result = def.element._zod.run({
        value: item,
        issues: []
      }, ctx);
      result instanceof Promise ? proms.push(result.then((result2) => handleArrayResult(result2, payload, i))) : handleArrayResult(result, payload, i);
    }
    return proms.length ? Promise.all(proms).then(() => payload) : payload;
  };
});
function handleObjectResult(result, final, key) {
  result.issues.length && final.issues.push(...prefixIssues(key, result.issues)), final.value[key] = result.value;
}
function handleOptionalObjectResult(result, final, key, input) {
  result.issues.length ? input[key] === void 0 ? key in input ? final.value[key] = void 0 : final.value[key] = result.value : final.issues.push(...prefixIssues(key, result.issues)) : result.value === void 0 ? key in input && (final.value[key] = void 0) : final.value[key] = result.value;
}
var $ZodObject = /* @__PURE__ */ $constructor("$ZodObject", (inst, def) => {
  $ZodType.init(inst, def);
  let _normalized = cached(() => {
    let keys = Object.keys(def.shape);
    for (let k of keys)
      if (!(def.shape[k] instanceof $ZodType))
        throw new Error(`Invalid element at key "${k}": expected a Zod schema`);
    let okeys = optionalKeys(def.shape);
    return {
      shape: def.shape,
      keys,
      keySet: new Set(keys),
      numKeys: keys.length,
      optionalKeys: new Set(okeys)
    };
  });
  defineLazy(inst._zod, "propValues", () => {
    let shape = def.shape, propValues = {};
    for (let key in shape) {
      let field = shape[key]._zod;
      if (field.values) {
        propValues[key] ?? (propValues[key] = /* @__PURE__ */ new Set());
        for (let v of field.values)
          propValues[key].add(v);
      }
    }
    return propValues;
  });
  let generateFastpass = (shape) => {
    let doc = new Doc(["shape", "payload", "ctx"]), normalized = _normalized.value, parseStr = (key) => {
      let k = esc(key);
      return `shape[${k}]._zod.run({ value: input[${k}], issues: [] }, ctx)`;
    };
    doc.write("const input = payload.value;");
    let ids = /* @__PURE__ */ Object.create(null), counter = 0;
    for (let key of normalized.keys)
      ids[key] = `key_${counter++}`;
    doc.write("const newResult = {}");
    for (let key of normalized.keys)
      if (normalized.optionalKeys.has(key)) {
        let id = ids[key];
        doc.write(`const ${id} = ${parseStr(key)};`);
        let k = esc(key);
        doc.write(`
        if (${id}.issues.length) {
          if (input[${k}] === undefined) {
            if (${k} in input) {
              newResult[${k}] = undefined;
            }
          } else {
            payload.issues = payload.issues.concat(
              ${id}.issues.map((iss) => ({
                ...iss,
                path: iss.path ? [${k}, ...iss.path] : [${k}],
              }))
            );
          }
        } else if (${id}.value === undefined) {
          if (${k} in input) newResult[${k}] = undefined;
        } else {
          newResult[${k}] = ${id}.value;
        }
        `);
      } else {
        let id = ids[key];
        doc.write(`const ${id} = ${parseStr(key)};`), doc.write(`
          if (${id}.issues.length) payload.issues = payload.issues.concat(${id}.issues.map(iss => ({
            ...iss,
            path: iss.path ? [${esc(key)}, ...iss.path] : [${esc(key)}]
          })));`), doc.write(`newResult[${esc(key)}] = ${id}.value`);
      }
    doc.write("payload.value = newResult;"), doc.write("return payload;");
    let fn = doc.compile();
    return (payload, ctx) => fn(shape, payload, ctx);
  }, fastpass, isObject2 = isObject, jit = !globalConfig.jitless, fastEnabled = jit && allowsEval.value, catchall = def.catchall, value;
  inst._zod.parse = (payload, ctx) => {
    value ?? (value = _normalized.value);
    let input = payload.value;
    if (!isObject2(input))
      return payload.issues.push({
        expected: "object",
        code: "invalid_type",
        input,
        inst
      }), payload;
    let proms = [];
    if (jit && fastEnabled && ctx?.async === !1 && ctx.jitless !== !0)
      fastpass || (fastpass = generateFastpass(def.shape)), payload = fastpass(payload, ctx);
    else {
      payload.value = {};
      let shape = value.shape;
      for (let key of value.keys) {
        let el = shape[key], r = el._zod.run({ value: input[key], issues: [] }, ctx), isOptional = el._zod.optin === "optional" && el._zod.optout === "optional";
        r instanceof Promise ? proms.push(r.then((r2) => isOptional ? handleOptionalObjectResult(r2, payload, key, input) : handleObjectResult(r2, payload, key))) : isOptional ? handleOptionalObjectResult(r, payload, key, input) : handleObjectResult(r, payload, key);
      }
    }
    if (!catchall)
      return proms.length ? Promise.all(proms).then(() => payload) : payload;
    let unrecognized = [], keySet = value.keySet, _catchall = catchall._zod, t = _catchall.def.type;
    for (let key of Object.keys(input)) {
      if (keySet.has(key))
        continue;
      if (t === "never") {
        unrecognized.push(key);
        continue;
      }
      let r = _catchall.run({ value: input[key], issues: [] }, ctx);
      r instanceof Promise ? proms.push(r.then((r2) => handleObjectResult(r2, payload, key))) : handleObjectResult(r, payload, key);
    }
    return unrecognized.length && payload.issues.push({
      code: "unrecognized_keys",
      keys: unrecognized,
      input,
      inst
    }), proms.length ? Promise.all(proms).then(() => payload) : payload;
  };
});
function handleUnionResults(results, final, inst, ctx) {
  for (let result of results)
    if (result.issues.length === 0)
      return final.value = result.value, final;
  return final.issues.push({
    code: "invalid_union",
    input: final.value,
    inst,
    errors: results.map((result) => result.issues.map((iss) => finalizeIssue(iss, ctx, config())))
  }), final;
}
var $ZodUnion = /* @__PURE__ */ $constructor("$ZodUnion", (inst, def) => {
  $ZodType.init(inst, def), defineLazy(inst._zod, "optin", () => def.options.some((o) => o._zod.optin === "optional") ? "optional" : void 0), defineLazy(inst._zod, "optout", () => def.options.some((o) => o._zod.optout === "optional") ? "optional" : void 0), defineLazy(inst._zod, "values", () => {
    if (def.options.every((o) => o._zod.values))
      return new Set(def.options.flatMap((option) => Array.from(option._zod.values)));
  }), defineLazy(inst._zod, "pattern", () => {
    if (def.options.every((o) => o._zod.pattern)) {
      let patterns = def.options.map((o) => o._zod.pattern);
      return new RegExp(`^(${patterns.map((p) => cleanRegex(p.source)).join("|")})$`);
    }
  }), inst._zod.parse = (payload, ctx) => {
    let async = !1, results = [];
    for (let option of def.options) {
      let result = option._zod.run({
        value: payload.value,
        issues: []
      }, ctx);
      if (result instanceof Promise)
        results.push(result), async = !0;
      else {
        if (result.issues.length === 0)
          return result;
        results.push(result);
      }
    }
    return async ? Promise.all(results).then((results2) => handleUnionResults(results2, payload, inst, ctx)) : handleUnionResults(results, payload, inst, ctx);
  };
}), $ZodDiscriminatedUnion = /* @__PURE__ */ $constructor("$ZodDiscriminatedUnion", (inst, def) => {
  $ZodUnion.init(inst, def);
  let _super = inst._zod.parse;
  defineLazy(inst._zod, "propValues", () => {
    let propValues = {};
    for (let option of def.options) {
      let pv = option._zod.propValues;
      if (!pv || Object.keys(pv).length === 0)
        throw new Error(`Invalid discriminated union option at index "${def.options.indexOf(option)}"`);
      for (let [k, v] of Object.entries(pv)) {
        propValues[k] || (propValues[k] = /* @__PURE__ */ new Set());
        for (let val of v)
          propValues[k].add(val);
      }
    }
    return propValues;
  });
  let disc = cached(() => {
    let opts = def.options, map = /* @__PURE__ */ new Map();
    for (let o of opts) {
      let values = o._zod.propValues[def.discriminator];
      if (!values || values.size === 0)
        throw new Error(`Invalid discriminated union option at index "${def.options.indexOf(o)}"`);
      for (let v of values) {
        if (map.has(v))
          throw new Error(`Duplicate discriminator value "${String(v)}"`);
        map.set(v, o);
      }
    }
    return map;
  });
  inst._zod.parse = (payload, ctx) => {
    let input = payload.value;
    if (!isObject(input))
      return payload.issues.push({
        code: "invalid_type",
        expected: "object",
        input,
        inst
      }), payload;
    let opt = disc.value.get(input?.[def.discriminator]);
    return opt ? opt._zod.run(payload, ctx) : def.unionFallback ? _super(payload, ctx) : (payload.issues.push({
      code: "invalid_union",
      errors: [],
      note: "No matching discriminator",
      input,
      path: [def.discriminator],
      inst
    }), payload);
  };
}), $ZodIntersection = /* @__PURE__ */ $constructor("$ZodIntersection", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.parse = (payload, ctx) => {
    let input = payload.value, left = def.left._zod.run({ value: input, issues: [] }, ctx), right = def.right._zod.run({ value: input, issues: [] }, ctx);
    return left instanceof Promise || right instanceof Promise ? Promise.all([left, right]).then(([left2, right2]) => handleIntersectionResults(payload, left2, right2)) : handleIntersectionResults(payload, left, right);
  };
});
function mergeValues2(a, b) {
  if (a === b)
    return { valid: !0, data: a };
  if (a instanceof Date && b instanceof Date && +a == +b)
    return { valid: !0, data: a };
  if (isPlainObject(a) && isPlainObject(b)) {
    let bKeys = Object.keys(b), sharedKeys = Object.keys(a).filter((key) => bKeys.indexOf(key) !== -1), newObj = { ...a, ...b };
    for (let key of sharedKeys) {
      let sharedValue = mergeValues2(a[key], b[key]);
      if (!sharedValue.valid)
        return {
          valid: !1,
          mergeErrorPath: [key, ...sharedValue.mergeErrorPath]
        };
      newObj[key] = sharedValue.data;
    }
    return { valid: !0, data: newObj };
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length)
      return { valid: !1, mergeErrorPath: [] };
    let newArray = [];
    for (let index = 0; index < a.length; index++) {
      let itemA = a[index], itemB = b[index], sharedValue = mergeValues2(itemA, itemB);
      if (!sharedValue.valid)
        return {
          valid: !1,
          mergeErrorPath: [index, ...sharedValue.mergeErrorPath]
        };
      newArray.push(sharedValue.data);
    }
    return { valid: !0, data: newArray };
  }
  return { valid: !1, mergeErrorPath: [] };
}
function handleIntersectionResults(result, left, right) {
  if (left.issues.length && result.issues.push(...left.issues), right.issues.length && result.issues.push(...right.issues), aborted(result))
    return result;
  let merged = mergeValues2(left.value, right.value);
  if (!merged.valid)
    throw new Error(`Unmergable intersection. Error path: ${JSON.stringify(merged.mergeErrorPath)}`);
  return result.value = merged.data, result;
}
var $ZodRecord = /* @__PURE__ */ $constructor("$ZodRecord", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.parse = (payload, ctx) => {
    let input = payload.value;
    if (!isPlainObject(input))
      return payload.issues.push({
        expected: "record",
        code: "invalid_type",
        input,
        inst
      }), payload;
    let proms = [];
    if (def.keyType._zod.values) {
      let values = def.keyType._zod.values;
      payload.value = {};
      for (let key of values)
        if (typeof key == "string" || typeof key == "number" || typeof key == "symbol") {
          let result = def.valueType._zod.run({ value: input[key], issues: [] }, ctx);
          result instanceof Promise ? proms.push(result.then((result2) => {
            result2.issues.length && payload.issues.push(...prefixIssues(key, result2.issues)), payload.value[key] = result2.value;
          })) : (result.issues.length && payload.issues.push(...prefixIssues(key, result.issues)), payload.value[key] = result.value);
        }
      let unrecognized;
      for (let key in input)
        values.has(key) || (unrecognized = unrecognized ?? [], unrecognized.push(key));
      unrecognized && unrecognized.length > 0 && payload.issues.push({
        code: "unrecognized_keys",
        input,
        inst,
        keys: unrecognized
      });
    } else {
      payload.value = {};
      for (let key of Reflect.ownKeys(input)) {
        if (key === "__proto__")
          continue;
        let keyResult = def.keyType._zod.run({ value: key, issues: [] }, ctx);
        if (keyResult instanceof Promise)
          throw new Error("Async schemas not supported in object keys currently");
        if (keyResult.issues.length) {
          payload.issues.push({
            origin: "record",
            code: "invalid_key",
            issues: keyResult.issues.map((iss) => finalizeIssue(iss, ctx, config())),
            input: key,
            path: [key],
            inst
          }), payload.value[keyResult.value] = keyResult.value;
          continue;
        }
        let result = def.valueType._zod.run({ value: input[key], issues: [] }, ctx);
        result instanceof Promise ? proms.push(result.then((result2) => {
          result2.issues.length && payload.issues.push(...prefixIssues(key, result2.issues)), payload.value[keyResult.value] = result2.value;
        })) : (result.issues.length && payload.issues.push(...prefixIssues(key, result.issues)), payload.value[keyResult.value] = result.value);
      }
    }
    return proms.length ? Promise.all(proms).then(() => payload) : payload;
  };
});
var $ZodEnum = /* @__PURE__ */ $constructor("$ZodEnum", (inst, def) => {
  $ZodType.init(inst, def);
  let values = getEnumValues(def.entries);
  inst._zod.values = new Set(values), inst._zod.pattern = new RegExp(`^(${values.filter((k) => propertyKeyTypes.has(typeof k)).map((o) => typeof o == "string" ? escapeRegex(o) : o.toString()).join("|")})$`), inst._zod.parse = (payload, _ctx) => {
    let input = payload.value;
    return inst._zod.values.has(input) || payload.issues.push({
      code: "invalid_value",
      values,
      input,
      inst
    }), payload;
  };
}), $ZodLiteral = /* @__PURE__ */ $constructor("$ZodLiteral", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.values = new Set(def.values), inst._zod.pattern = new RegExp(`^(${def.values.map((o) => typeof o == "string" ? escapeRegex(o) : o ? o.toString() : String(o)).join("|")})$`), inst._zod.parse = (payload, _ctx) => {
    let input = payload.value;
    return inst._zod.values.has(input) || payload.issues.push({
      code: "invalid_value",
      values: def.values,
      input,
      inst
    }), payload;
  };
});
var $ZodTransform = /* @__PURE__ */ $constructor("$ZodTransform", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.parse = (payload, _ctx) => {
    let _out = def.transform(payload.value, payload);
    if (_ctx.async)
      return (_out instanceof Promise ? _out : Promise.resolve(_out)).then((output2) => (payload.value = output2, payload));
    if (_out instanceof Promise)
      throw new $ZodAsyncError();
    return payload.value = _out, payload;
  };
}), $ZodOptional = /* @__PURE__ */ $constructor("$ZodOptional", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.optin = "optional", inst._zod.optout = "optional", defineLazy(inst._zod, "values", () => def.innerType._zod.values ? /* @__PURE__ */ new Set([...def.innerType._zod.values, void 0]) : void 0), defineLazy(inst._zod, "pattern", () => {
    let pattern = def.innerType._zod.pattern;
    return pattern ? new RegExp(`^(${cleanRegex(pattern.source)})?$`) : void 0;
  }), inst._zod.parse = (payload, ctx) => def.innerType._zod.optin === "optional" ? def.innerType._zod.run(payload, ctx) : payload.value === void 0 ? payload : def.innerType._zod.run(payload, ctx);
}), $ZodNullable = /* @__PURE__ */ $constructor("$ZodNullable", (inst, def) => {
  $ZodType.init(inst, def), defineLazy(inst._zod, "optin", () => def.innerType._zod.optin), defineLazy(inst._zod, "optout", () => def.innerType._zod.optout), defineLazy(inst._zod, "pattern", () => {
    let pattern = def.innerType._zod.pattern;
    return pattern ? new RegExp(`^(${cleanRegex(pattern.source)}|null)$`) : void 0;
  }), defineLazy(inst._zod, "values", () => def.innerType._zod.values ? /* @__PURE__ */ new Set([...def.innerType._zod.values, null]) : void 0), inst._zod.parse = (payload, ctx) => payload.value === null ? payload : def.innerType._zod.run(payload, ctx);
}), $ZodDefault = /* @__PURE__ */ $constructor("$ZodDefault", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.optin = "optional", defineLazy(inst._zod, "values", () => def.innerType._zod.values), inst._zod.parse = (payload, ctx) => {
    if (payload.value === void 0)
      return payload.value = def.defaultValue, payload;
    let result = def.innerType._zod.run(payload, ctx);
    return result instanceof Promise ? result.then((result2) => handleDefaultResult(result2, def)) : handleDefaultResult(result, def);
  };
});
function handleDefaultResult(payload, def) {
  return payload.value === void 0 && (payload.value = def.defaultValue), payload;
}
var $ZodPrefault = /* @__PURE__ */ $constructor("$ZodPrefault", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.optin = "optional", defineLazy(inst._zod, "values", () => def.innerType._zod.values), inst._zod.parse = (payload, ctx) => (payload.value === void 0 && (payload.value = def.defaultValue), def.innerType._zod.run(payload, ctx));
}), $ZodNonOptional = /* @__PURE__ */ $constructor("$ZodNonOptional", (inst, def) => {
  $ZodType.init(inst, def), defineLazy(inst._zod, "values", () => {
    let v = def.innerType._zod.values;
    return v ? new Set([...v].filter((x) => x !== void 0)) : void 0;
  }), inst._zod.parse = (payload, ctx) => {
    let result = def.innerType._zod.run(payload, ctx);
    return result instanceof Promise ? result.then((result2) => handleNonOptionalResult(result2, inst)) : handleNonOptionalResult(result, inst);
  };
});
function handleNonOptionalResult(payload, inst) {
  return !payload.issues.length && payload.value === void 0 && payload.issues.push({
    code: "invalid_type",
    expected: "nonoptional",
    input: payload.value,
    inst
  }), payload;
}
var $ZodCatch = /* @__PURE__ */ $constructor("$ZodCatch", (inst, def) => {
  $ZodType.init(inst, def), inst._zod.optin = "optional", defineLazy(inst._zod, "optout", () => def.innerType._zod.optout), defineLazy(inst._zod, "values", () => def.innerType._zod.values), inst._zod.parse = (payload, ctx) => {
    let result = def.innerType._zod.run(payload, ctx);
    return result instanceof Promise ? result.then((result2) => (payload.value = result2.value, result2.issues.length && (payload.value = def.catchValue({
      ...payload,
      error: {
        issues: result2.issues.map((iss) => finalizeIssue(iss, ctx, config()))
      },
      input: payload.value
    }), payload.issues = []), payload)) : (payload.value = result.value, result.issues.length && (payload.value = def.catchValue({
      ...payload,
      error: {
        issues: result.issues.map((iss) => finalizeIssue(iss, ctx, config()))
      },
      input: payload.value
    }), payload.issues = []), payload);
  };
});
var $ZodPipe = /* @__PURE__ */ $constructor("$ZodPipe", (inst, def) => {
  $ZodType.init(inst, def), defineLazy(inst._zod, "values", () => def.in._zod.values), defineLazy(inst._zod, "optin", () => def.in._zod.optin), defineLazy(inst._zod, "optout", () => def.out._zod.optout), inst._zod.parse = (payload, ctx) => {
    let left = def.in._zod.run(payload, ctx);
    return left instanceof Promise ? left.then((left2) => handlePipeResult(left2, def, ctx)) : handlePipeResult(left, def, ctx);
  };
});
function handlePipeResult(left, def, ctx) {
  return aborted(left) ? left : def.out._zod.run({ value: left.value, issues: left.issues }, ctx);
}
var $ZodReadonly = /* @__PURE__ */ $constructor("$ZodReadonly", (inst, def) => {
  $ZodType.init(inst, def), defineLazy(inst._zod, "propValues", () => def.innerType._zod.propValues), defineLazy(inst._zod, "values", () => def.innerType._zod.values), defineLazy(inst._zod, "optin", () => def.innerType._zod.optin), defineLazy(inst._zod, "optout", () => def.innerType._zod.optout), inst._zod.parse = (payload, ctx) => {
    let result = def.innerType._zod.run(payload, ctx);
    return result instanceof Promise ? result.then(handleReadonlyResult) : handleReadonlyResult(result);
  };
});
function handleReadonlyResult(payload) {
  return payload.value = Object.freeze(payload.value), payload;
}
var $ZodCustom = /* @__PURE__ */ $constructor("$ZodCustom", (inst, def) => {
  $ZodCheck.init(inst, def), $ZodType.init(inst, def), inst._zod.parse = (payload, _) => payload, inst._zod.check = (payload) => {
    let input = payload.value, r = def.fn(input);
    if (r instanceof Promise)
      return r.then((r2) => handleRefineResult(r2, payload, input, inst));
    handleRefineResult(r, payload, input, inst);
  };
});
function handleRefineResult(result, payload, input, inst) {
  if (!result) {
    let _iss = {
      code: "custom",
      input,
      inst,
      // incorporates params.error into issue reporting
      path: [...inst._zod.def.path ?? []],
      // incorporates params.error into issue reporting
      continue: !inst._zod.def.abort
      // params: inst._zod.def.params,
    };
    inst._zod.def.params && (_iss.params = inst._zod.def.params), payload.issues.push(issue(_iss));
  }
}

// node_modules/zod/v4/locales/en.js
var parsedType = (data) => {
  let t = typeof data;
  switch (t) {
    case "number":
      return Number.isNaN(data) ? "NaN" : "number";
    case "object": {
      if (Array.isArray(data))
        return "array";
      if (data === null)
        return "null";
      if (Object.getPrototypeOf(data) !== Object.prototype && data.constructor)
        return data.constructor.name;
    }
  }
  return t;
}, error = () => {
  let Sizable = {
    string: { unit: "characters", verb: "to have" },
    file: { unit: "bytes", verb: "to have" },
    array: { unit: "items", verb: "to have" },
    set: { unit: "items", verb: "to have" }
  };
  function getSizing(origin) {
    return Sizable[origin] ?? null;
  }
  let Nouns = {
    regex: "input",
    email: "email address",
    url: "URL",
    emoji: "emoji",
    uuid: "UUID",
    uuidv4: "UUIDv4",
    uuidv6: "UUIDv6",
    nanoid: "nanoid",
    guid: "GUID",
    cuid: "cuid",
    cuid2: "cuid2",
    ulid: "ULID",
    xid: "XID",
    ksuid: "KSUID",
    datetime: "ISO datetime",
    date: "ISO date",
    time: "ISO time",
    duration: "ISO duration",
    ipv4: "IPv4 address",
    ipv6: "IPv6 address",
    cidrv4: "IPv4 range",
    cidrv6: "IPv6 range",
    base64: "base64-encoded string",
    base64url: "base64url-encoded string",
    json_string: "JSON string",
    e164: "E.164 number",
    jwt: "JWT",
    template_literal: "input"
  };
  return (issue2) => {
    switch (issue2.code) {
      case "invalid_type":
        return `Invalid input: expected ${issue2.expected}, received ${parsedType(issue2.input)}`;
      case "invalid_value":
        return issue2.values.length === 1 ? `Invalid input: expected ${stringifyPrimitive(issue2.values[0])}` : `Invalid option: expected one of ${joinValues(issue2.values, "|")}`;
      case "too_big": {
        let adj = issue2.inclusive ? "<=" : "<", sizing = getSizing(issue2.origin);
        return sizing ? `Too big: expected ${issue2.origin ?? "value"} to have ${adj}${issue2.maximum.toString()} ${sizing.unit ?? "elements"}` : `Too big: expected ${issue2.origin ?? "value"} to be ${adj}${issue2.maximum.toString()}`;
      }
      case "too_small": {
        let adj = issue2.inclusive ? ">=" : ">", sizing = getSizing(issue2.origin);
        return sizing ? `Too small: expected ${issue2.origin} to have ${adj}${issue2.minimum.toString()} ${sizing.unit}` : `Too small: expected ${issue2.origin} to be ${adj}${issue2.minimum.toString()}`;
      }
      case "invalid_format": {
        let _issue = issue2;
        return _issue.format === "starts_with" ? `Invalid string: must start with "${_issue.prefix}"` : _issue.format === "ends_with" ? `Invalid string: must end with "${_issue.suffix}"` : _issue.format === "includes" ? `Invalid string: must include "${_issue.includes}"` : _issue.format === "regex" ? `Invalid string: must match pattern ${_issue.pattern}` : `Invalid ${Nouns[_issue.format] ?? issue2.format}`;
      }
      case "not_multiple_of":
        return `Invalid number: must be a multiple of ${issue2.divisor}`;
      case "unrecognized_keys":
        return `Unrecognized key${issue2.keys.length > 1 ? "s" : ""}: ${joinValues(issue2.keys, ", ")}`;
      case "invalid_key":
        return `Invalid key in ${issue2.origin}`;
      case "invalid_union":
        return "Invalid input";
      case "invalid_element":
        return `Invalid value in ${issue2.origin}`;
      default:
        return "Invalid input";
    }
  };
};
function en_default2() {
  return {
    localeError: error()
  };
}

// node_modules/zod/v4/core/registries.js
var $output = Symbol("ZodOutput"), $input = Symbol("ZodInput"), $ZodRegistry = class {
  constructor() {
    this._map = /* @__PURE__ */ new Map(), this._idmap = /* @__PURE__ */ new Map();
  }
  add(schema, ..._meta) {
    let meta = _meta[0];
    if (this._map.set(schema, meta), meta && typeof meta == "object" && "id" in meta) {
      if (this._idmap.has(meta.id))
        throw new Error(`ID ${meta.id} already exists in the registry`);
      this._idmap.set(meta.id, schema);
    }
    return this;
  }
  clear() {
    return this._map = /* @__PURE__ */ new Map(), this._idmap = /* @__PURE__ */ new Map(), this;
  }
  remove(schema) {
    let meta = this._map.get(schema);
    return meta && typeof meta == "object" && "id" in meta && this._idmap.delete(meta.id), this._map.delete(schema), this;
  }
  get(schema) {
    let p = schema._zod.parent;
    if (p) {
      let pm = { ...this.get(p) ?? {} };
      return delete pm.id, { ...pm, ...this._map.get(schema) };
    }
    return this._map.get(schema);
  }
  has(schema) {
    return this._map.has(schema);
  }
};
function registry() {
  return new $ZodRegistry();
}
var globalRegistry = /* @__PURE__ */ registry();

// node_modules/zod/v4/core/api.js
function _string(Class2, params) {
  return new Class2({
    type: "string",
    ...normalizeParams(params)
  });
}
function _email(Class2, params) {
  return new Class2({
    type: "string",
    format: "email",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _guid(Class2, params) {
  return new Class2({
    type: "string",
    format: "guid",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _uuid(Class2, params) {
  return new Class2({
    type: "string",
    format: "uuid",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _uuidv4(Class2, params) {
  return new Class2({
    type: "string",
    format: "uuid",
    check: "string_format",
    abort: !1,
    version: "v4",
    ...normalizeParams(params)
  });
}
function _uuidv6(Class2, params) {
  return new Class2({
    type: "string",
    format: "uuid",
    check: "string_format",
    abort: !1,
    version: "v6",
    ...normalizeParams(params)
  });
}
function _uuidv7(Class2, params) {
  return new Class2({
    type: "string",
    format: "uuid",
    check: "string_format",
    abort: !1,
    version: "v7",
    ...normalizeParams(params)
  });
}
function _url(Class2, params) {
  return new Class2({
    type: "string",
    format: "url",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _emoji2(Class2, params) {
  return new Class2({
    type: "string",
    format: "emoji",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _nanoid(Class2, params) {
  return new Class2({
    type: "string",
    format: "nanoid",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _cuid(Class2, params) {
  return new Class2({
    type: "string",
    format: "cuid",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _cuid2(Class2, params) {
  return new Class2({
    type: "string",
    format: "cuid2",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _ulid(Class2, params) {
  return new Class2({
    type: "string",
    format: "ulid",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _xid(Class2, params) {
  return new Class2({
    type: "string",
    format: "xid",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _ksuid(Class2, params) {
  return new Class2({
    type: "string",
    format: "ksuid",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _ipv4(Class2, params) {
  return new Class2({
    type: "string",
    format: "ipv4",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _ipv6(Class2, params) {
  return new Class2({
    type: "string",
    format: "ipv6",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _cidrv4(Class2, params) {
  return new Class2({
    type: "string",
    format: "cidrv4",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _cidrv6(Class2, params) {
  return new Class2({
    type: "string",
    format: "cidrv6",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _base64(Class2, params) {
  return new Class2({
    type: "string",
    format: "base64",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _base64url(Class2, params) {
  return new Class2({
    type: "string",
    format: "base64url",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _e164(Class2, params) {
  return new Class2({
    type: "string",
    format: "e164",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _jwt(Class2, params) {
  return new Class2({
    type: "string",
    format: "jwt",
    check: "string_format",
    abort: !1,
    ...normalizeParams(params)
  });
}
function _isoDateTime(Class2, params) {
  return new Class2({
    type: "string",
    format: "datetime",
    check: "string_format",
    offset: !1,
    local: !1,
    precision: null,
    ...normalizeParams(params)
  });
}
function _isoDate(Class2, params) {
  return new Class2({
    type: "string",
    format: "date",
    check: "string_format",
    ...normalizeParams(params)
  });
}
function _isoTime(Class2, params) {
  return new Class2({
    type: "string",
    format: "time",
    check: "string_format",
    precision: null,
    ...normalizeParams(params)
  });
}
function _isoDuration(Class2, params) {
  return new Class2({
    type: "string",
    format: "duration",
    check: "string_format",
    ...normalizeParams(params)
  });
}
function _number(Class2, params) {
  return new Class2({
    type: "number",
    checks: [],
    ...normalizeParams(params)
  });
}
function _int(Class2, params) {
  return new Class2({
    type: "number",
    check: "number_format",
    abort: !1,
    format: "safeint",
    ...normalizeParams(params)
  });
}
function _boolean(Class2, params) {
  return new Class2({
    type: "boolean",
    ...normalizeParams(params)
  });
}
function _null2(Class2, params) {
  return new Class2({
    type: "null",
    ...normalizeParams(params)
  });
}
function _unknown(Class2) {
  return new Class2({
    type: "unknown"
  });
}
function _never(Class2, params) {
  return new Class2({
    type: "never",
    ...normalizeParams(params)
  });
}
function _lt(value, params) {
  return new $ZodCheckLessThan({
    check: "less_than",
    ...normalizeParams(params),
    value,
    inclusive: !1
  });
}
function _lte(value, params) {
  return new $ZodCheckLessThan({
    check: "less_than",
    ...normalizeParams(params),
    value,
    inclusive: !0
  });
}
function _gt(value, params) {
  return new $ZodCheckGreaterThan({
    check: "greater_than",
    ...normalizeParams(params),
    value,
    inclusive: !1
  });
}
function _gte(value, params) {
  return new $ZodCheckGreaterThan({
    check: "greater_than",
    ...normalizeParams(params),
    value,
    inclusive: !0
  });
}
function _multipleOf(value, params) {
  return new $ZodCheckMultipleOf({
    check: "multiple_of",
    ...normalizeParams(params),
    value
  });
}
function _maxLength(maximum, params) {
  return new $ZodCheckMaxLength({
    check: "max_length",
    ...normalizeParams(params),
    maximum
  });
}
function _minLength(minimum, params) {
  return new $ZodCheckMinLength({
    check: "min_length",
    ...normalizeParams(params),
    minimum
  });
}
function _length(length, params) {
  return new $ZodCheckLengthEquals({
    check: "length_equals",
    ...normalizeParams(params),
    length
  });
}
function _regex(pattern, params) {
  return new $ZodCheckRegex({
    check: "string_format",
    format: "regex",
    ...normalizeParams(params),
    pattern
  });
}
function _lowercase(params) {
  return new $ZodCheckLowerCase({
    check: "string_format",
    format: "lowercase",
    ...normalizeParams(params)
  });
}
function _uppercase(params) {
  return new $ZodCheckUpperCase({
    check: "string_format",
    format: "uppercase",
    ...normalizeParams(params)
  });
}
function _includes(includes, params) {
  return new $ZodCheckIncludes({
    check: "string_format",
    format: "includes",
    ...normalizeParams(params),
    includes
  });
}
function _startsWith(prefix, params) {
  return new $ZodCheckStartsWith({
    check: "string_format",
    format: "starts_with",
    ...normalizeParams(params),
    prefix
  });
}
function _endsWith(suffix, params) {
  return new $ZodCheckEndsWith({
    check: "string_format",
    format: "ends_with",
    ...normalizeParams(params),
    suffix
  });
}
function _overwrite(tx) {
  return new $ZodCheckOverwrite({
    check: "overwrite",
    tx
  });
}
function _normalize(form) {
  return _overwrite((input) => input.normalize(form));
}
function _trim() {
  return _overwrite((input) => input.trim());
}
function _toLowerCase() {
  return _overwrite((input) => input.toLowerCase());
}
function _toUpperCase() {
  return _overwrite((input) => input.toUpperCase());
}
function _array(Class2, element, params) {
  return new Class2({
    type: "array",
    element,
    // get element() {
    //   return element;
    // },
    ...normalizeParams(params)
  });
}
function _custom(Class2, fn, _params) {
  let norm = normalizeParams(_params);
  return norm.abort ?? (norm.abort = !0), new Class2({
    type: "custom",
    check: "custom",
    fn,
    ...norm
  });
}
function _refine(Class2, fn, _params) {
  return new Class2({
    type: "custom",
    check: "custom",
    fn,
    ...normalizeParams(_params)
  });
}

// node_modules/zod/v4/core/to-json-schema.js
var JSONSchemaGenerator = class {
  constructor(params) {
    this.counter = 0, this.metadataRegistry = params?.metadata ?? globalRegistry, this.target = params?.target ?? "draft-2020-12", this.unrepresentable = params?.unrepresentable ?? "throw", this.override = params?.override ?? (() => {
    }), this.io = params?.io ?? "output", this.seen = /* @__PURE__ */ new Map();
  }
  process(schema, _params = { path: [], schemaPath: [] }) {
    var _a;
    let def = schema._zod.def, formatMap = {
      guid: "uuid",
      url: "uri",
      datetime: "date-time",
      json_string: "json-string",
      regex: ""
      // do not set
    }, seen = this.seen.get(schema);
    if (seen)
      return seen.count++, _params.schemaPath.includes(schema) && (seen.cycle = _params.path), seen.schema;
    let result = { schema: {}, count: 1, cycle: void 0, path: _params.path };
    this.seen.set(schema, result);
    let overrideSchema = schema._zod.toJSONSchema?.();
    if (overrideSchema)
      result.schema = overrideSchema;
    else {
      let params = {
        ..._params,
        schemaPath: [..._params.schemaPath, schema],
        path: _params.path
      }, parent = schema._zod.parent;
      if (parent)
        result.ref = parent, this.process(parent, params), this.seen.get(parent).isParent = !0;
      else {
        let _json = result.schema;
        switch (def.type) {
          case "string": {
            let json2 = _json;
            json2.type = "string";
            let { minimum, maximum, format, patterns, contentEncoding } = schema._zod.bag;
            if (typeof minimum == "number" && (json2.minLength = minimum), typeof maximum == "number" && (json2.maxLength = maximum), format && (json2.format = formatMap[format] ?? format, json2.format === "" && delete json2.format), contentEncoding && (json2.contentEncoding = contentEncoding), patterns && patterns.size > 0) {
              let regexes = [...patterns];
              regexes.length === 1 ? json2.pattern = regexes[0].source : regexes.length > 1 && (result.schema.allOf = [
                ...regexes.map((regex) => ({
                  ...this.target === "draft-7" ? { type: "string" } : {},
                  pattern: regex.source
                }))
              ]);
            }
            break;
          }
          case "number": {
            let json2 = _json, { minimum, maximum, format, multipleOf, exclusiveMaximum, exclusiveMinimum } = schema._zod.bag;
            typeof format == "string" && format.includes("int") ? json2.type = "integer" : json2.type = "number", typeof exclusiveMinimum == "number" && (json2.exclusiveMinimum = exclusiveMinimum), typeof minimum == "number" && (json2.minimum = minimum, typeof exclusiveMinimum == "number" && (exclusiveMinimum >= minimum ? delete json2.minimum : delete json2.exclusiveMinimum)), typeof exclusiveMaximum == "number" && (json2.exclusiveMaximum = exclusiveMaximum), typeof maximum == "number" && (json2.maximum = maximum, typeof exclusiveMaximum == "number" && (exclusiveMaximum <= maximum ? delete json2.maximum : delete json2.exclusiveMaximum)), typeof multipleOf == "number" && (json2.multipleOf = multipleOf);
            break;
          }
          case "boolean": {
            let json2 = _json;
            json2.type = "boolean";
            break;
          }
          case "bigint": {
            if (this.unrepresentable === "throw")
              throw new Error("BigInt cannot be represented in JSON Schema");
            break;
          }
          case "symbol": {
            if (this.unrepresentable === "throw")
              throw new Error("Symbols cannot be represented in JSON Schema");
            break;
          }
          case "null": {
            _json.type = "null";
            break;
          }
          case "any":
            break;
          case "unknown":
            break;
          case "undefined": {
            if (this.unrepresentable === "throw")
              throw new Error("Undefined cannot be represented in JSON Schema");
            break;
          }
          case "void": {
            if (this.unrepresentable === "throw")
              throw new Error("Void cannot be represented in JSON Schema");
            break;
          }
          case "never": {
            _json.not = {};
            break;
          }
          case "date": {
            if (this.unrepresentable === "throw")
              throw new Error("Date cannot be represented in JSON Schema");
            break;
          }
          case "array": {
            let json2 = _json, { minimum, maximum } = schema._zod.bag;
            typeof minimum == "number" && (json2.minItems = minimum), typeof maximum == "number" && (json2.maxItems = maximum), json2.type = "array", json2.items = this.process(def.element, { ...params, path: [...params.path, "items"] });
            break;
          }
          case "object": {
            let json2 = _json;
            json2.type = "object", json2.properties = {};
            let shape = def.shape;
            for (let key in shape)
              json2.properties[key] = this.process(shape[key], {
                ...params,
                path: [...params.path, "properties", key]
              });
            let allKeys = new Set(Object.keys(shape)), requiredKeys = new Set([...allKeys].filter((key) => {
              let v = def.shape[key]._zod;
              return this.io === "input" ? v.optin === void 0 : v.optout === void 0;
            }));
            requiredKeys.size > 0 && (json2.required = Array.from(requiredKeys)), def.catchall?._zod.def.type === "never" ? json2.additionalProperties = !1 : def.catchall ? def.catchall && (json2.additionalProperties = this.process(def.catchall, {
              ...params,
              path: [...params.path, "additionalProperties"]
            })) : this.io === "output" && (json2.additionalProperties = !1);
            break;
          }
          case "union": {
            let json2 = _json;
            json2.anyOf = def.options.map((x, i) => this.process(x, {
              ...params,
              path: [...params.path, "anyOf", i]
            }));
            break;
          }
          case "intersection": {
            let json2 = _json, a = this.process(def.left, {
              ...params,
              path: [...params.path, "allOf", 0]
            }), b = this.process(def.right, {
              ...params,
              path: [...params.path, "allOf", 1]
            }), isSimpleIntersection = (val) => "allOf" in val && Object.keys(val).length === 1, allOf = [
              ...isSimpleIntersection(a) ? a.allOf : [a],
              ...isSimpleIntersection(b) ? b.allOf : [b]
            ];
            json2.allOf = allOf;
            break;
          }
          case "tuple": {
            let json2 = _json;
            json2.type = "array";
            let prefixItems = def.items.map((x, i) => this.process(x, { ...params, path: [...params.path, "prefixItems", i] }));
            if (this.target === "draft-2020-12" ? json2.prefixItems = prefixItems : json2.items = prefixItems, def.rest) {
              let rest = this.process(def.rest, {
                ...params,
                path: [...params.path, "items"]
              });
              this.target === "draft-2020-12" ? json2.items = rest : json2.additionalItems = rest;
            }
            def.rest && (json2.items = this.process(def.rest, {
              ...params,
              path: [...params.path, "items"]
            }));
            let { minimum, maximum } = schema._zod.bag;
            typeof minimum == "number" && (json2.minItems = minimum), typeof maximum == "number" && (json2.maxItems = maximum);
            break;
          }
          case "record": {
            let json2 = _json;
            json2.type = "object", json2.propertyNames = this.process(def.keyType, { ...params, path: [...params.path, "propertyNames"] }), json2.additionalProperties = this.process(def.valueType, {
              ...params,
              path: [...params.path, "additionalProperties"]
            });
            break;
          }
          case "map": {
            if (this.unrepresentable === "throw")
              throw new Error("Map cannot be represented in JSON Schema");
            break;
          }
          case "set": {
            if (this.unrepresentable === "throw")
              throw new Error("Set cannot be represented in JSON Schema");
            break;
          }
          case "enum": {
            let json2 = _json, values = getEnumValues(def.entries);
            values.every((v) => typeof v == "number") && (json2.type = "number"), values.every((v) => typeof v == "string") && (json2.type = "string"), json2.enum = values;
            break;
          }
          case "literal": {
            let json2 = _json, vals = [];
            for (let val of def.values)
              if (val === void 0) {
                if (this.unrepresentable === "throw")
                  throw new Error("Literal `undefined` cannot be represented in JSON Schema");
              } else if (typeof val == "bigint") {
                if (this.unrepresentable === "throw")
                  throw new Error("BigInt literals cannot be represented in JSON Schema");
                vals.push(Number(val));
              } else
                vals.push(val);
            if (vals.length !== 0)
              if (vals.length === 1) {
                let val = vals[0];
                json2.type = val === null ? "null" : typeof val, json2.const = val;
              } else
                vals.every((v) => typeof v == "number") && (json2.type = "number"), vals.every((v) => typeof v == "string") && (json2.type = "string"), vals.every((v) => typeof v == "boolean") && (json2.type = "string"), vals.every((v) => v === null) && (json2.type = "null"), json2.enum = vals;
            break;
          }
          case "file": {
            let json2 = _json, file = {
              type: "string",
              format: "binary",
              contentEncoding: "binary"
            }, { minimum, maximum, mime } = schema._zod.bag;
            minimum !== void 0 && (file.minLength = minimum), maximum !== void 0 && (file.maxLength = maximum), mime ? mime.length === 1 ? (file.contentMediaType = mime[0], Object.assign(json2, file)) : json2.anyOf = mime.map((m) => ({ ...file, contentMediaType: m })) : Object.assign(json2, file);
            break;
          }
          case "transform": {
            if (this.unrepresentable === "throw")
              throw new Error("Transforms cannot be represented in JSON Schema");
            break;
          }
          case "nullable": {
            let inner = this.process(def.innerType, params);
            _json.anyOf = [inner, { type: "null" }];
            break;
          }
          case "nonoptional": {
            this.process(def.innerType, params), result.ref = def.innerType;
            break;
          }
          case "success": {
            let json2 = _json;
            json2.type = "boolean";
            break;
          }
          case "default": {
            this.process(def.innerType, params), result.ref = def.innerType, _json.default = JSON.parse(JSON.stringify(def.defaultValue));
            break;
          }
          case "prefault": {
            this.process(def.innerType, params), result.ref = def.innerType, this.io === "input" && (_json._prefault = JSON.parse(JSON.stringify(def.defaultValue)));
            break;
          }
          case "catch": {
            this.process(def.innerType, params), result.ref = def.innerType;
            let catchValue;
            try {
              catchValue = def.catchValue(void 0);
            } catch {
              throw new Error("Dynamic catch values are not supported in JSON Schema");
            }
            _json.default = catchValue;
            break;
          }
          case "nan": {
            if (this.unrepresentable === "throw")
              throw new Error("NaN cannot be represented in JSON Schema");
            break;
          }
          case "template_literal": {
            let json2 = _json, pattern = schema._zod.pattern;
            if (!pattern)
              throw new Error("Pattern not found in template literal");
            json2.type = "string", json2.pattern = pattern.source;
            break;
          }
          case "pipe": {
            let innerType = this.io === "input" ? def.in._zod.def.type === "transform" ? def.out : def.in : def.out;
            this.process(innerType, params), result.ref = innerType;
            break;
          }
          case "readonly": {
            this.process(def.innerType, params), result.ref = def.innerType, _json.readOnly = !0;
            break;
          }
          // passthrough types
          case "promise": {
            this.process(def.innerType, params), result.ref = def.innerType;
            break;
          }
          case "optional": {
            this.process(def.innerType, params), result.ref = def.innerType;
            break;
          }
          case "lazy": {
            let innerType = schema._zod.innerType;
            this.process(innerType, params), result.ref = innerType;
            break;
          }
          case "custom": {
            if (this.unrepresentable === "throw")
              throw new Error("Custom types cannot be represented in JSON Schema");
            break;
          }
          default:
        }
      }
    }
    let meta = this.metadataRegistry.get(schema);
    return meta && Object.assign(result.schema, meta), this.io === "input" && isTransforming(schema) && (delete result.schema.examples, delete result.schema.default), this.io === "input" && result.schema._prefault && ((_a = result.schema).default ?? (_a.default = result.schema._prefault)), delete result.schema._prefault, this.seen.get(schema).schema;
  }
  emit(schema, _params) {
    let params = {
      cycles: _params?.cycles ?? "ref",
      reused: _params?.reused ?? "inline",
      // unrepresentable: _params?.unrepresentable ?? "throw",
      // uri: _params?.uri ?? ((id) => `${id}`),
      external: _params?.external ?? void 0
    }, root = this.seen.get(schema);
    if (!root)
      throw new Error("Unprocessed schema. This is a bug in Zod.");
    let makeURI = (entry) => {
      let defsSegment = this.target === "draft-2020-12" ? "$defs" : "definitions";
      if (params.external) {
        let externalId = params.external.registry.get(entry[0])?.id, uriGenerator = params.external.uri ?? ((id2) => id2);
        if (externalId)
          return { ref: uriGenerator(externalId) };
        let id = entry[1].defId ?? entry[1].schema.id ?? `schema${this.counter++}`;
        return entry[1].defId = id, { defId: id, ref: `${uriGenerator("__shared")}#/${defsSegment}/${id}` };
      }
      if (entry[1] === root)
        return { ref: "#" };
      let defUriPrefix = `#/${defsSegment}/`, defId = entry[1].schema.id ?? `__schema${this.counter++}`;
      return { defId, ref: defUriPrefix + defId };
    }, extractToDef = (entry) => {
      if (entry[1].schema.$ref)
        return;
      let seen = entry[1], { ref: ref2, defId } = makeURI(entry);
      seen.def = { ...seen.schema }, defId && (seen.defId = defId);
      let schema2 = seen.schema;
      for (let key in schema2)
        delete schema2[key];
      schema2.$ref = ref2;
    };
    if (params.cycles === "throw")
      for (let entry of this.seen.entries()) {
        let seen = entry[1];
        if (seen.cycle)
          throw new Error(`Cycle detected: #/${seen.cycle?.join("/")}/<root>

Set the \`cycles\` parameter to \`"ref"\` to resolve cyclical schemas with defs.`);
      }
    for (let entry of this.seen.entries()) {
      let seen = entry[1];
      if (schema === entry[0]) {
        extractToDef(entry);
        continue;
      }
      if (params.external) {
        let ext = params.external.registry.get(entry[0])?.id;
        if (schema !== entry[0] && ext) {
          extractToDef(entry);
          continue;
        }
      }
      if (this.metadataRegistry.get(entry[0])?.id) {
        extractToDef(entry);
        continue;
      }
      if (seen.cycle) {
        extractToDef(entry);
        continue;
      }
      if (seen.count > 1 && params.reused === "ref") {
        extractToDef(entry);
        continue;
      }
    }
    let flattenRef = (zodSchema, params2) => {
      let seen = this.seen.get(zodSchema), schema2 = seen.def ?? seen.schema, _cached = { ...schema2 };
      if (seen.ref === null)
        return;
      let ref2 = seen.ref;
      if (seen.ref = null, ref2) {
        flattenRef(ref2, params2);
        let refSchema = this.seen.get(ref2).schema;
        refSchema.$ref && params2.target === "draft-7" ? (schema2.allOf = schema2.allOf ?? [], schema2.allOf.push(refSchema)) : (Object.assign(schema2, refSchema), Object.assign(schema2, _cached));
      }
      seen.isParent || this.override({
        zodSchema,
        jsonSchema: schema2,
        path: seen.path ?? []
      });
    };
    for (let entry of [...this.seen.entries()].reverse())
      flattenRef(entry[0], { target: this.target });
    let result = {};
    if (this.target === "draft-2020-12" ? result.$schema = "https://json-schema.org/draft/2020-12/schema" : this.target === "draft-7" ? result.$schema = "http://json-schema.org/draft-07/schema#" : console.warn(`Invalid target: ${this.target}`), params.external?.uri) {
      let id = params.external.registry.get(schema)?.id;
      if (!id)
        throw new Error("Schema is missing an `id` property");
      result.$id = params.external.uri(id);
    }
    Object.assign(result, root.def);
    let defs = params.external?.defs ?? {};
    for (let entry of this.seen.entries()) {
      let seen = entry[1];
      seen.def && seen.defId && (defs[seen.defId] = seen.def);
    }
    params.external || Object.keys(defs).length > 0 && (this.target === "draft-2020-12" ? result.$defs = defs : result.definitions = defs);
    try {
      return JSON.parse(JSON.stringify(result));
    } catch {
      throw new Error("Error converting schema to JSON.");
    }
  }
};
function toJSONSchema(input, _params) {
  if (input instanceof $ZodRegistry) {
    let gen2 = new JSONSchemaGenerator(_params), defs = {};
    for (let entry of input._idmap.entries()) {
      let [_, schema] = entry;
      gen2.process(schema);
    }
    let schemas = {}, external = {
      registry: input,
      uri: _params?.uri,
      defs
    };
    for (let entry of input._idmap.entries()) {
      let [key, schema] = entry;
      schemas[key] = gen2.emit(schema, {
        ..._params,
        external
      });
    }
    if (Object.keys(defs).length > 0) {
      let defsSegment = gen2.target === "draft-2020-12" ? "$defs" : "definitions";
      schemas.__shared = {
        [defsSegment]: defs
      };
    }
    return { schemas };
  }
  let gen = new JSONSchemaGenerator(_params);
  return gen.process(input), gen.emit(input, _params);
}
function isTransforming(_schema, _ctx) {
  let ctx = _ctx ?? { seen: /* @__PURE__ */ new Set() };
  if (ctx.seen.has(_schema))
    return !1;
  ctx.seen.add(_schema);
  let def = _schema._zod.def;
  switch (def.type) {
    case "string":
    case "number":
    case "bigint":
    case "boolean":
    case "date":
    case "symbol":
    case "undefined":
    case "null":
    case "any":
    case "unknown":
    case "never":
    case "void":
    case "literal":
    case "enum":
    case "nan":
    case "file":
    case "template_literal":
      return !1;
    case "array":
      return isTransforming(def.element, ctx);
    case "object": {
      for (let key in def.shape)
        if (isTransforming(def.shape[key], ctx))
          return !0;
      return !1;
    }
    case "union": {
      for (let option of def.options)
        if (isTransforming(option, ctx))
          return !0;
      return !1;
    }
    case "intersection":
      return isTransforming(def.left, ctx) || isTransforming(def.right, ctx);
    case "tuple": {
      for (let item of def.items)
        if (isTransforming(item, ctx))
          return !0;
      return !!(def.rest && isTransforming(def.rest, ctx));
    }
    case "record":
      return isTransforming(def.keyType, ctx) || isTransforming(def.valueType, ctx);
    case "map":
      return isTransforming(def.keyType, ctx) || isTransforming(def.valueType, ctx);
    case "set":
      return isTransforming(def.valueType, ctx);
    // inner types
    case "promise":
    case "optional":
    case "nonoptional":
    case "nullable":
    case "readonly":
      return isTransforming(def.innerType, ctx);
    case "lazy":
      return isTransforming(def.getter(), ctx);
    case "default":
      return isTransforming(def.innerType, ctx);
    case "prefault":
      return isTransforming(def.innerType, ctx);
    case "custom":
      return !1;
    case "transform":
      return !0;
    case "pipe":
      return isTransforming(def.in, ctx) || isTransforming(def.out, ctx);
    case "success":
      return !1;
    case "catch":
      return !1;
    default:
  }
  throw new Error(`Unknown schema type: ${def.type}`);
}

// node_modules/zod/v4/mini/schemas.js
var ZodMiniType = /* @__PURE__ */ $constructor("ZodMiniType", (inst, def) => {
  if (!inst._zod)
    throw new Error("Uninitialized schema in ZodMiniType.");
  $ZodType.init(inst, def), inst.def = def, inst.parse = (data, params) => parse(inst, data, params, { callee: inst.parse }), inst.safeParse = (data, params) => safeParse(inst, data, params), inst.parseAsync = async (data, params) => parseAsync(inst, data, params, { callee: inst.parseAsync }), inst.safeParseAsync = async (data, params) => safeParseAsync(inst, data, params), inst.check = (...checks) => inst.clone(
    {
      ...def,
      checks: [
        ...def.checks ?? [],
        ...checks.map((ch) => typeof ch == "function" ? { _zod: { check: ch, def: { check: "custom" }, onattach: [] } } : ch)
      ]
    }
    // { parent: true }
  ), inst.clone = (_def, params) => clone(inst, _def, params), inst.brand = () => inst, inst.register = ((reg, meta) => (reg.add(inst, meta), inst));
});
var ZodMiniObject = /* @__PURE__ */ $constructor("ZodMiniObject", (inst, def) => {
  $ZodObject.init(inst, def), ZodMiniType.init(inst, def), util_exports.defineLazy(inst, "shape", () => def.shape);
});
function object(shape, params) {
  let def = {
    type: "object",
    get shape() {
      return util_exports.assignProp(this, "shape", { ...shape }), this.shape;
    },
    ...util_exports.normalizeParams(params)
  };
  return new ZodMiniObject(def);
}

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/zod-compat.js
function isZ4Schema(s) {
  return !!s._zod;
}
function objectFromShape(shape) {
  let values = Object.values(shape);
  if (values.length === 0)
    return object({});
  let allV4 = values.every(isZ4Schema), allV3 = values.every((s) => !isZ4Schema(s));
  if (allV4)
    return object(shape);
  if (allV3)
    return objectType(shape);
  throw new Error("Mixed Zod versions detected in object shape.");
}
function safeParse2(schema, data) {
  return isZ4Schema(schema) ? safeParse(schema, data) : schema.safeParse(data);
}
async function safeParseAsync2(schema, data) {
  return isZ4Schema(schema) ? await safeParseAsync(schema, data) : await schema.safeParseAsync(data);
}
function getObjectShape(schema) {
  if (!schema)
    return;
  let rawShape;
  if (isZ4Schema(schema) ? rawShape = schema._zod?.def?.shape : rawShape = schema.shape, !!rawShape) {
    if (typeof rawShape == "function")
      try {
        return rawShape();
      } catch {
        return;
      }
    return rawShape;
  }
}
function normalizeObjectSchema(schema) {
  if (schema) {
    if (typeof schema == "object") {
      let asV3 = schema, asV4 = schema;
      if (!asV3._def && !asV4._zod) {
        let values = Object.values(schema);
        if (values.length > 0 && values.every((v) => typeof v == "object" && v !== null && (v._def !== void 0 || v._zod !== void 0 || typeof v.parse == "function")))
          return objectFromShape(schema);
      }
    }
    if (isZ4Schema(schema)) {
      let def = schema._zod?.def;
      if (def && (def.type === "object" || def.shape !== void 0))
        return schema;
    } else if (schema.shape !== void 0)
      return schema;
  }
}
function getDotPath(path3) {
  return path3.length === 0 ? "object root" : path3.reduce((acc, seg, index) => index === 0 ? String(seg) : typeof seg == "number" ? `${acc}[${seg}]` : `${acc}.${seg}`, "");
}
function getParseErrorMessage(error2) {
  if (error2 && typeof error2 == "object") {
    if ("issues" in error2 && Array.isArray(error2.issues) && error2.issues.length > 0)
      return error2.issues.map((i) => i.path?.length ? `${i.message} at ${getDotPath(i.path)}` : i.message).join(`
`);
    if ("message" in error2 && typeof error2.message == "string")
      return error2.message;
    try {
      return JSON.stringify(error2);
    } catch {
      return String(error2);
    }
  }
  return String(error2);
}
function getSchemaDescription(schema) {
  return schema.description;
}
function isSchemaOptional(schema) {
  if (isZ4Schema(schema))
    return schema._zod?.def?.type === "optional";
  let v3Schema = schema;
  return typeof schema.isOptional == "function" ? schema.isOptional() : v3Schema._def?.typeName === "ZodOptional";
}
function getLiteralValue(schema) {
  if (isZ4Schema(schema)) {
    let def2 = schema._zod?.def;
    if (def2) {
      if (def2.value !== void 0)
        return def2.value;
      if (Array.isArray(def2.values) && def2.values.length > 0)
        return def2.values[0];
    }
  }
  let def = schema._def;
  if (def) {
    if (def.value !== void 0)
      return def.value;
    if (Array.isArray(def.values) && def.values.length > 0)
      return def.values[0];
  }
  let directValue = schema.value;
  if (directValue !== void 0)
    return directValue;
}

// node_modules/zod/v4/classic/iso.js
var iso_exports2 = {};
__export(iso_exports2, {
  ZodISODate: () => ZodISODate,
  ZodISODateTime: () => ZodISODateTime,
  ZodISODuration: () => ZodISODuration,
  ZodISOTime: () => ZodISOTime,
  date: () => date2,
  datetime: () => datetime2,
  duration: () => duration2,
  time: () => time2
});
var ZodISODateTime = /* @__PURE__ */ $constructor("ZodISODateTime", (inst, def) => {
  $ZodISODateTime.init(inst, def), ZodStringFormat.init(inst, def);
});
function datetime2(params) {
  return _isoDateTime(ZodISODateTime, params);
}
var ZodISODate = /* @__PURE__ */ $constructor("ZodISODate", (inst, def) => {
  $ZodISODate.init(inst, def), ZodStringFormat.init(inst, def);
});
function date2(params) {
  return _isoDate(ZodISODate, params);
}
var ZodISOTime = /* @__PURE__ */ $constructor("ZodISOTime", (inst, def) => {
  $ZodISOTime.init(inst, def), ZodStringFormat.init(inst, def);
});
function time2(params) {
  return _isoTime(ZodISOTime, params);
}
var ZodISODuration = /* @__PURE__ */ $constructor("ZodISODuration", (inst, def) => {
  $ZodISODuration.init(inst, def), ZodStringFormat.init(inst, def);
});
function duration2(params) {
  return _isoDuration(ZodISODuration, params);
}

// node_modules/zod/v4/classic/errors.js
var initializer2 = (inst, issues) => {
  $ZodError.init(inst, issues), inst.name = "ZodError", Object.defineProperties(inst, {
    format: {
      value: (mapper) => formatError(inst, mapper)
      // enumerable: false,
    },
    flatten: {
      value: (mapper) => flattenError(inst, mapper)
      // enumerable: false,
    },
    addIssue: {
      value: (issue2) => inst.issues.push(issue2)
      // enumerable: false,
    },
    addIssues: {
      value: (issues2) => inst.issues.push(...issues2)
      // enumerable: false,
    },
    isEmpty: {
      get() {
        return inst.issues.length === 0;
      }
      // enumerable: false,
    }
  });
}, ZodError2 = $constructor("ZodError", initializer2), ZodRealError = $constructor("ZodError", initializer2, {
  Parent: Error
});

// node_modules/zod/v4/classic/parse.js
var parse2 = /* @__PURE__ */ _parse(ZodRealError), parseAsync2 = /* @__PURE__ */ _parseAsync(ZodRealError), safeParse3 = /* @__PURE__ */ _safeParse(ZodRealError), safeParseAsync3 = /* @__PURE__ */ _safeParseAsync(ZodRealError);

// node_modules/zod/v4/classic/schemas.js
var ZodType2 = /* @__PURE__ */ $constructor("ZodType", (inst, def) => ($ZodType.init(inst, def), inst.def = def, Object.defineProperty(inst, "_def", { value: def }), inst.check = (...checks) => inst.clone(
  {
    ...def,
    checks: [
      ...def.checks ?? [],
      ...checks.map((ch) => typeof ch == "function" ? { _zod: { check: ch, def: { check: "custom" }, onattach: [] } } : ch)
    ]
  }
  // { parent: true }
), inst.clone = (def2, params) => clone(inst, def2, params), inst.brand = () => inst, inst.register = ((reg, meta) => (reg.add(inst, meta), inst)), inst.parse = (data, params) => parse2(inst, data, params, { callee: inst.parse }), inst.safeParse = (data, params) => safeParse3(inst, data, params), inst.parseAsync = async (data, params) => parseAsync2(inst, data, params, { callee: inst.parseAsync }), inst.safeParseAsync = async (data, params) => safeParseAsync3(inst, data, params), inst.spa = inst.safeParseAsync, inst.refine = (check2, params) => inst.check(refine(check2, params)), inst.superRefine = (refinement) => inst.check(superRefine(refinement)), inst.overwrite = (fn) => inst.check(_overwrite(fn)), inst.optional = () => optional(inst), inst.nullable = () => nullable(inst), inst.nullish = () => optional(nullable(inst)), inst.nonoptional = (params) => nonoptional(inst, params), inst.array = () => array(inst), inst.or = (arg) => union([inst, arg]), inst.and = (arg) => intersection(inst, arg), inst.transform = (tx) => pipe(inst, transform(tx)), inst.default = (def2) => _default(inst, def2), inst.prefault = (def2) => prefault(inst, def2), inst.catch = (params) => _catch(inst, params), inst.pipe = (target) => pipe(inst, target), inst.readonly = () => readonly(inst), inst.describe = (description) => {
  let cl = inst.clone();
  return globalRegistry.add(cl, { description }), cl;
}, Object.defineProperty(inst, "description", {
  get() {
    return globalRegistry.get(inst)?.description;
  },
  configurable: !0
}), inst.meta = (...args) => {
  if (args.length === 0)
    return globalRegistry.get(inst);
  let cl = inst.clone();
  return globalRegistry.add(cl, args[0]), cl;
}, inst.isOptional = () => inst.safeParse(void 0).success, inst.isNullable = () => inst.safeParse(null).success, inst)), _ZodString = /* @__PURE__ */ $constructor("_ZodString", (inst, def) => {
  $ZodString.init(inst, def), ZodType2.init(inst, def);
  let bag = inst._zod.bag;
  inst.format = bag.format ?? null, inst.minLength = bag.minimum ?? null, inst.maxLength = bag.maximum ?? null, inst.regex = (...args) => inst.check(_regex(...args)), inst.includes = (...args) => inst.check(_includes(...args)), inst.startsWith = (...args) => inst.check(_startsWith(...args)), inst.endsWith = (...args) => inst.check(_endsWith(...args)), inst.min = (...args) => inst.check(_minLength(...args)), inst.max = (...args) => inst.check(_maxLength(...args)), inst.length = (...args) => inst.check(_length(...args)), inst.nonempty = (...args) => inst.check(_minLength(1, ...args)), inst.lowercase = (params) => inst.check(_lowercase(params)), inst.uppercase = (params) => inst.check(_uppercase(params)), inst.trim = () => inst.check(_trim()), inst.normalize = (...args) => inst.check(_normalize(...args)), inst.toLowerCase = () => inst.check(_toLowerCase()), inst.toUpperCase = () => inst.check(_toUpperCase());
}), ZodString2 = /* @__PURE__ */ $constructor("ZodString", (inst, def) => {
  $ZodString.init(inst, def), _ZodString.init(inst, def), inst.email = (params) => inst.check(_email(ZodEmail, params)), inst.url = (params) => inst.check(_url(ZodURL, params)), inst.jwt = (params) => inst.check(_jwt(ZodJWT, params)), inst.emoji = (params) => inst.check(_emoji2(ZodEmoji, params)), inst.guid = (params) => inst.check(_guid(ZodGUID, params)), inst.uuid = (params) => inst.check(_uuid(ZodUUID, params)), inst.uuidv4 = (params) => inst.check(_uuidv4(ZodUUID, params)), inst.uuidv6 = (params) => inst.check(_uuidv6(ZodUUID, params)), inst.uuidv7 = (params) => inst.check(_uuidv7(ZodUUID, params)), inst.nanoid = (params) => inst.check(_nanoid(ZodNanoID, params)), inst.guid = (params) => inst.check(_guid(ZodGUID, params)), inst.cuid = (params) => inst.check(_cuid(ZodCUID, params)), inst.cuid2 = (params) => inst.check(_cuid2(ZodCUID2, params)), inst.ulid = (params) => inst.check(_ulid(ZodULID, params)), inst.base64 = (params) => inst.check(_base64(ZodBase64, params)), inst.base64url = (params) => inst.check(_base64url(ZodBase64URL, params)), inst.xid = (params) => inst.check(_xid(ZodXID, params)), inst.ksuid = (params) => inst.check(_ksuid(ZodKSUID, params)), inst.ipv4 = (params) => inst.check(_ipv4(ZodIPv4, params)), inst.ipv6 = (params) => inst.check(_ipv6(ZodIPv6, params)), inst.cidrv4 = (params) => inst.check(_cidrv4(ZodCIDRv4, params)), inst.cidrv6 = (params) => inst.check(_cidrv6(ZodCIDRv6, params)), inst.e164 = (params) => inst.check(_e164(ZodE164, params)), inst.datetime = (params) => inst.check(datetime2(params)), inst.date = (params) => inst.check(date2(params)), inst.time = (params) => inst.check(time2(params)), inst.duration = (params) => inst.check(duration2(params));
});
function string2(params) {
  return _string(ZodString2, params);
}
var ZodStringFormat = /* @__PURE__ */ $constructor("ZodStringFormat", (inst, def) => {
  $ZodStringFormat.init(inst, def), _ZodString.init(inst, def);
}), ZodEmail = /* @__PURE__ */ $constructor("ZodEmail", (inst, def) => {
  $ZodEmail.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodGUID = /* @__PURE__ */ $constructor("ZodGUID", (inst, def) => {
  $ZodGUID.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodUUID = /* @__PURE__ */ $constructor("ZodUUID", (inst, def) => {
  $ZodUUID.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodURL = /* @__PURE__ */ $constructor("ZodURL", (inst, def) => {
  $ZodURL.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodEmoji = /* @__PURE__ */ $constructor("ZodEmoji", (inst, def) => {
  $ZodEmoji.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodNanoID = /* @__PURE__ */ $constructor("ZodNanoID", (inst, def) => {
  $ZodNanoID.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodCUID = /* @__PURE__ */ $constructor("ZodCUID", (inst, def) => {
  $ZodCUID.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodCUID2 = /* @__PURE__ */ $constructor("ZodCUID2", (inst, def) => {
  $ZodCUID2.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodULID = /* @__PURE__ */ $constructor("ZodULID", (inst, def) => {
  $ZodULID.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodXID = /* @__PURE__ */ $constructor("ZodXID", (inst, def) => {
  $ZodXID.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodKSUID = /* @__PURE__ */ $constructor("ZodKSUID", (inst, def) => {
  $ZodKSUID.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodIPv4 = /* @__PURE__ */ $constructor("ZodIPv4", (inst, def) => {
  $ZodIPv4.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodIPv6 = /* @__PURE__ */ $constructor("ZodIPv6", (inst, def) => {
  $ZodIPv6.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodCIDRv4 = /* @__PURE__ */ $constructor("ZodCIDRv4", (inst, def) => {
  $ZodCIDRv4.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodCIDRv6 = /* @__PURE__ */ $constructor("ZodCIDRv6", (inst, def) => {
  $ZodCIDRv6.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodBase64 = /* @__PURE__ */ $constructor("ZodBase64", (inst, def) => {
  $ZodBase64.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodBase64URL = /* @__PURE__ */ $constructor("ZodBase64URL", (inst, def) => {
  $ZodBase64URL.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodE164 = /* @__PURE__ */ $constructor("ZodE164", (inst, def) => {
  $ZodE164.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodJWT = /* @__PURE__ */ $constructor("ZodJWT", (inst, def) => {
  $ZodJWT.init(inst, def), ZodStringFormat.init(inst, def);
});
var ZodNumber2 = /* @__PURE__ */ $constructor("ZodNumber", (inst, def) => {
  $ZodNumber.init(inst, def), ZodType2.init(inst, def), inst.gt = (value, params) => inst.check(_gt(value, params)), inst.gte = (value, params) => inst.check(_gte(value, params)), inst.min = (value, params) => inst.check(_gte(value, params)), inst.lt = (value, params) => inst.check(_lt(value, params)), inst.lte = (value, params) => inst.check(_lte(value, params)), inst.max = (value, params) => inst.check(_lte(value, params)), inst.int = (params) => inst.check(int(params)), inst.safe = (params) => inst.check(int(params)), inst.positive = (params) => inst.check(_gt(0, params)), inst.nonnegative = (params) => inst.check(_gte(0, params)), inst.negative = (params) => inst.check(_lt(0, params)), inst.nonpositive = (params) => inst.check(_lte(0, params)), inst.multipleOf = (value, params) => inst.check(_multipleOf(value, params)), inst.step = (value, params) => inst.check(_multipleOf(value, params)), inst.finite = () => inst;
  let bag = inst._zod.bag;
  inst.minValue = Math.max(bag.minimum ?? Number.NEGATIVE_INFINITY, bag.exclusiveMinimum ?? Number.NEGATIVE_INFINITY) ?? null, inst.maxValue = Math.min(bag.maximum ?? Number.POSITIVE_INFINITY, bag.exclusiveMaximum ?? Number.POSITIVE_INFINITY) ?? null, inst.isInt = (bag.format ?? "").includes("int") || Number.isSafeInteger(bag.multipleOf ?? 0.5), inst.isFinite = !0, inst.format = bag.format ?? null;
});
function number2(params) {
  return _number(ZodNumber2, params);
}
var ZodNumberFormat = /* @__PURE__ */ $constructor("ZodNumberFormat", (inst, def) => {
  $ZodNumberFormat.init(inst, def), ZodNumber2.init(inst, def);
});
function int(params) {
  return _int(ZodNumberFormat, params);
}
var ZodBoolean2 = /* @__PURE__ */ $constructor("ZodBoolean", (inst, def) => {
  $ZodBoolean.init(inst, def), ZodType2.init(inst, def);
});
function boolean2(params) {
  return _boolean(ZodBoolean2, params);
}
var ZodNull2 = /* @__PURE__ */ $constructor("ZodNull", (inst, def) => {
  $ZodNull.init(inst, def), ZodType2.init(inst, def);
});
function _null3(params) {
  return _null2(ZodNull2, params);
}
var ZodUnknown2 = /* @__PURE__ */ $constructor("ZodUnknown", (inst, def) => {
  $ZodUnknown.init(inst, def), ZodType2.init(inst, def);
});
function unknown() {
  return _unknown(ZodUnknown2);
}
var ZodNever2 = /* @__PURE__ */ $constructor("ZodNever", (inst, def) => {
  $ZodNever.init(inst, def), ZodType2.init(inst, def);
});
function never(params) {
  return _never(ZodNever2, params);
}
var ZodArray2 = /* @__PURE__ */ $constructor("ZodArray", (inst, def) => {
  $ZodArray.init(inst, def), ZodType2.init(inst, def), inst.element = def.element, inst.min = (minLength, params) => inst.check(_minLength(minLength, params)), inst.nonempty = (params) => inst.check(_minLength(1, params)), inst.max = (maxLength, params) => inst.check(_maxLength(maxLength, params)), inst.length = (len, params) => inst.check(_length(len, params)), inst.unwrap = () => inst.element;
});
function array(element, params) {
  return _array(ZodArray2, element, params);
}
var ZodObject2 = /* @__PURE__ */ $constructor("ZodObject", (inst, def) => {
  $ZodObject.init(inst, def), ZodType2.init(inst, def), util_exports.defineLazy(inst, "shape", () => def.shape), inst.keyof = () => _enum(Object.keys(inst._zod.def.shape)), inst.catchall = (catchall) => inst.clone({ ...inst._zod.def, catchall }), inst.passthrough = () => inst.clone({ ...inst._zod.def, catchall: unknown() }), inst.loose = () => inst.clone({ ...inst._zod.def, catchall: unknown() }), inst.strict = () => inst.clone({ ...inst._zod.def, catchall: never() }), inst.strip = () => inst.clone({ ...inst._zod.def, catchall: void 0 }), inst.extend = (incoming) => util_exports.extend(inst, incoming), inst.merge = (other) => util_exports.merge(inst, other), inst.pick = (mask) => util_exports.pick(inst, mask), inst.omit = (mask) => util_exports.omit(inst, mask), inst.partial = (...args) => util_exports.partial(ZodOptional2, inst, args[0]), inst.required = (...args) => util_exports.required(ZodNonOptional, inst, args[0]);
});
function object2(shape, params) {
  let def = {
    type: "object",
    get shape() {
      return util_exports.assignProp(this, "shape", { ...shape }), this.shape;
    },
    ...util_exports.normalizeParams(params)
  };
  return new ZodObject2(def);
}
function looseObject(shape, params) {
  return new ZodObject2({
    type: "object",
    get shape() {
      return util_exports.assignProp(this, "shape", { ...shape }), this.shape;
    },
    catchall: unknown(),
    ...util_exports.normalizeParams(params)
  });
}
var ZodUnion2 = /* @__PURE__ */ $constructor("ZodUnion", (inst, def) => {
  $ZodUnion.init(inst, def), ZodType2.init(inst, def), inst.options = def.options;
});
function union(options, params) {
  return new ZodUnion2({
    type: "union",
    options,
    ...util_exports.normalizeParams(params)
  });
}
var ZodDiscriminatedUnion2 = /* @__PURE__ */ $constructor("ZodDiscriminatedUnion", (inst, def) => {
  ZodUnion2.init(inst, def), $ZodDiscriminatedUnion.init(inst, def);
});
function discriminatedUnion(discriminator, options, params) {
  return new ZodDiscriminatedUnion2({
    type: "union",
    options,
    discriminator,
    ...util_exports.normalizeParams(params)
  });
}
var ZodIntersection2 = /* @__PURE__ */ $constructor("ZodIntersection", (inst, def) => {
  $ZodIntersection.init(inst, def), ZodType2.init(inst, def);
});
function intersection(left, right) {
  return new ZodIntersection2({
    type: "intersection",
    left,
    right
  });
}
var ZodRecord2 = /* @__PURE__ */ $constructor("ZodRecord", (inst, def) => {
  $ZodRecord.init(inst, def), ZodType2.init(inst, def), inst.keyType = def.keyType, inst.valueType = def.valueType;
});
function record(keyType, valueType, params) {
  return new ZodRecord2({
    type: "record",
    keyType,
    valueType,
    ...util_exports.normalizeParams(params)
  });
}
var ZodEnum2 = /* @__PURE__ */ $constructor("ZodEnum", (inst, def) => {
  $ZodEnum.init(inst, def), ZodType2.init(inst, def), inst.enum = def.entries, inst.options = Object.values(def.entries);
  let keys = new Set(Object.keys(def.entries));
  inst.extract = (values, params) => {
    let newEntries = {};
    for (let value of values)
      if (keys.has(value))
        newEntries[value] = def.entries[value];
      else
        throw new Error(`Key ${value} not found in enum`);
    return new ZodEnum2({
      ...def,
      checks: [],
      ...util_exports.normalizeParams(params),
      entries: newEntries
    });
  }, inst.exclude = (values, params) => {
    let newEntries = { ...def.entries };
    for (let value of values)
      if (keys.has(value))
        delete newEntries[value];
      else
        throw new Error(`Key ${value} not found in enum`);
    return new ZodEnum2({
      ...def,
      checks: [],
      ...util_exports.normalizeParams(params),
      entries: newEntries
    });
  };
});
function _enum(values, params) {
  let entries = Array.isArray(values) ? Object.fromEntries(values.map((v) => [v, v])) : values;
  return new ZodEnum2({
    type: "enum",
    entries,
    ...util_exports.normalizeParams(params)
  });
}
var ZodLiteral2 = /* @__PURE__ */ $constructor("ZodLiteral", (inst, def) => {
  $ZodLiteral.init(inst, def), ZodType2.init(inst, def), inst.values = new Set(def.values), Object.defineProperty(inst, "value", {
    get() {
      if (def.values.length > 1)
        throw new Error("This schema contains multiple valid literal values. Use `.values` instead.");
      return def.values[0];
    }
  });
});
function literal(value, params) {
  return new ZodLiteral2({
    type: "literal",
    values: Array.isArray(value) ? value : [value],
    ...util_exports.normalizeParams(params)
  });
}
var ZodTransform = /* @__PURE__ */ $constructor("ZodTransform", (inst, def) => {
  $ZodTransform.init(inst, def), ZodType2.init(inst, def), inst._zod.parse = (payload, _ctx) => {
    payload.addIssue = (issue2) => {
      if (typeof issue2 == "string")
        payload.issues.push(util_exports.issue(issue2, payload.value, def));
      else {
        let _issue = issue2;
        _issue.fatal && (_issue.continue = !1), _issue.code ?? (_issue.code = "custom"), _issue.input ?? (_issue.input = payload.value), _issue.inst ?? (_issue.inst = inst), _issue.continue ?? (_issue.continue = !0), payload.issues.push(util_exports.issue(_issue));
      }
    };
    let output = def.transform(payload.value, payload);
    return output instanceof Promise ? output.then((output2) => (payload.value = output2, payload)) : (payload.value = output, payload);
  };
});
function transform(fn) {
  return new ZodTransform({
    type: "transform",
    transform: fn
  });
}
var ZodOptional2 = /* @__PURE__ */ $constructor("ZodOptional", (inst, def) => {
  $ZodOptional.init(inst, def), ZodType2.init(inst, def), inst.unwrap = () => inst._zod.def.innerType;
});
function optional(innerType) {
  return new ZodOptional2({
    type: "optional",
    innerType
  });
}
var ZodNullable2 = /* @__PURE__ */ $constructor("ZodNullable", (inst, def) => {
  $ZodNullable.init(inst, def), ZodType2.init(inst, def), inst.unwrap = () => inst._zod.def.innerType;
});
function nullable(innerType) {
  return new ZodNullable2({
    type: "nullable",
    innerType
  });
}
var ZodDefault2 = /* @__PURE__ */ $constructor("ZodDefault", (inst, def) => {
  $ZodDefault.init(inst, def), ZodType2.init(inst, def), inst.unwrap = () => inst._zod.def.innerType, inst.removeDefault = inst.unwrap;
});
function _default(innerType, defaultValue) {
  return new ZodDefault2({
    type: "default",
    innerType,
    get defaultValue() {
      return typeof defaultValue == "function" ? defaultValue() : defaultValue;
    }
  });
}
var ZodPrefault = /* @__PURE__ */ $constructor("ZodPrefault", (inst, def) => {
  $ZodPrefault.init(inst, def), ZodType2.init(inst, def), inst.unwrap = () => inst._zod.def.innerType;
});
function prefault(innerType, defaultValue) {
  return new ZodPrefault({
    type: "prefault",
    innerType,
    get defaultValue() {
      return typeof defaultValue == "function" ? defaultValue() : defaultValue;
    }
  });
}
var ZodNonOptional = /* @__PURE__ */ $constructor("ZodNonOptional", (inst, def) => {
  $ZodNonOptional.init(inst, def), ZodType2.init(inst, def), inst.unwrap = () => inst._zod.def.innerType;
});
function nonoptional(innerType, params) {
  return new ZodNonOptional({
    type: "nonoptional",
    innerType,
    ...util_exports.normalizeParams(params)
  });
}
var ZodCatch2 = /* @__PURE__ */ $constructor("ZodCatch", (inst, def) => {
  $ZodCatch.init(inst, def), ZodType2.init(inst, def), inst.unwrap = () => inst._zod.def.innerType, inst.removeCatch = inst.unwrap;
});
function _catch(innerType, catchValue) {
  return new ZodCatch2({
    type: "catch",
    innerType,
    catchValue: typeof catchValue == "function" ? catchValue : () => catchValue
  });
}
var ZodPipe = /* @__PURE__ */ $constructor("ZodPipe", (inst, def) => {
  $ZodPipe.init(inst, def), ZodType2.init(inst, def), inst.in = def.in, inst.out = def.out;
});
function pipe(in_, out) {
  return new ZodPipe({
    type: "pipe",
    in: in_,
    out
    // ...util.normalizeParams(params),
  });
}
var ZodReadonly2 = /* @__PURE__ */ $constructor("ZodReadonly", (inst, def) => {
  $ZodReadonly.init(inst, def), ZodType2.init(inst, def);
});
function readonly(innerType) {
  return new ZodReadonly2({
    type: "readonly",
    innerType
  });
}
var ZodCustom = /* @__PURE__ */ $constructor("ZodCustom", (inst, def) => {
  $ZodCustom.init(inst, def), ZodType2.init(inst, def);
});
function check(fn) {
  let ch = new $ZodCheck({
    check: "custom"
    // ...util.normalizeParams(params),
  });
  return ch._zod.check = fn, ch;
}
function custom2(fn, _params) {
  return _custom(ZodCustom, fn ?? (() => !0), _params);
}
function refine(fn, _params = {}) {
  return _refine(ZodCustom, fn, _params);
}
function superRefine(fn) {
  let ch = check((payload) => (payload.addIssue = (issue2) => {
    if (typeof issue2 == "string")
      payload.issues.push(util_exports.issue(issue2, payload.value, ch._zod.def));
    else {
      let _issue = issue2;
      _issue.fatal && (_issue.continue = !1), _issue.code ?? (_issue.code = "custom"), _issue.input ?? (_issue.input = payload.value), _issue.inst ?? (_issue.inst = ch), _issue.continue ?? (_issue.continue = !ch._zod.def.abort), payload.issues.push(util_exports.issue(_issue));
    }
  }, fn(payload.value, payload)));
  return ch;
}
function preprocess(fn, schema) {
  return pipe(transform(fn), schema);
}

// node_modules/zod/v4/classic/external.js
config(en_default2());

// node_modules/@modelcontextprotocol/sdk/dist/esm/types.js
var LATEST_PROTOCOL_VERSION = "2025-11-25", DEFAULT_NEGOTIATED_PROTOCOL_VERSION = "2025-03-26", SUPPORTED_PROTOCOL_VERSIONS = [LATEST_PROTOCOL_VERSION, "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07"], RELATED_TASK_META_KEY = "io.modelcontextprotocol/related-task", JSONRPC_VERSION = "2.0", AssertObjectSchema = custom2((v) => v !== null && (typeof v == "object" || typeof v == "function")), ProgressTokenSchema = union([string2(), number2().int()]), CursorSchema = string2(), TaskCreationParamsSchema = looseObject({
  /**
   * Requested duration in milliseconds to retain task from creation.
   */
  ttl: number2().optional(),
  /**
   * Time in milliseconds to wait between task status requests.
   */
  pollInterval: number2().optional()
}), TaskMetadataSchema = object2({
  ttl: number2().optional()
}), RelatedTaskMetadataSchema = object2({
  taskId: string2()
}), RequestMetaSchema = looseObject({
  /**
   * If specified, the caller is requesting out-of-band progress notifications for this request (as represented by notifications/progress). The value of this parameter is an opaque token that will be attached to any subsequent notifications. The receiver is not obligated to provide these notifications.
   */
  progressToken: ProgressTokenSchema.optional(),
  /**
   * If specified, this request is related to the provided task.
   */
  [RELATED_TASK_META_KEY]: RelatedTaskMetadataSchema.optional()
}), BaseRequestParamsSchema = object2({
  /**
   * See [General fields: `_meta`](/specification/draft/basic/index#meta) for notes on `_meta` usage.
   */
  _meta: RequestMetaSchema.optional()
}), TaskAugmentedRequestParamsSchema = BaseRequestParamsSchema.extend({
  /**
   * If specified, the caller is requesting task-augmented execution for this request.
   * The request will return a CreateTaskResult immediately, and the actual result can be
   * retrieved later via tasks/result.
   *
   * Task augmentation is subject to capability negotiation - receivers MUST declare support
   * for task augmentation of specific request types in their capabilities.
   */
  task: TaskMetadataSchema.optional()
}), isTaskAugmentedRequestParams = (value) => TaskAugmentedRequestParamsSchema.safeParse(value).success, RequestSchema = object2({
  method: string2(),
  params: BaseRequestParamsSchema.loose().optional()
}), NotificationsParamsSchema = object2({
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: RequestMetaSchema.optional()
}), NotificationSchema = object2({
  method: string2(),
  params: NotificationsParamsSchema.loose().optional()
}), ResultSchema = looseObject({
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: RequestMetaSchema.optional()
}), RequestIdSchema = union([string2(), number2().int()]), JSONRPCRequestSchema = object2({
  jsonrpc: literal(JSONRPC_VERSION),
  id: RequestIdSchema,
  ...RequestSchema.shape
}).strict(), isJSONRPCRequest = (value) => JSONRPCRequestSchema.safeParse(value).success, JSONRPCNotificationSchema = object2({
  jsonrpc: literal(JSONRPC_VERSION),
  ...NotificationSchema.shape
}).strict(), isJSONRPCNotification = (value) => JSONRPCNotificationSchema.safeParse(value).success, JSONRPCResultResponseSchema = object2({
  jsonrpc: literal(JSONRPC_VERSION),
  id: RequestIdSchema,
  result: ResultSchema
}).strict(), isJSONRPCResultResponse = (value) => JSONRPCResultResponseSchema.safeParse(value).success;
var ErrorCode;
(function(ErrorCode2) {
  ErrorCode2[ErrorCode2.ConnectionClosed = -32e3] = "ConnectionClosed", ErrorCode2[ErrorCode2.RequestTimeout = -32001] = "RequestTimeout", ErrorCode2[ErrorCode2.ParseError = -32700] = "ParseError", ErrorCode2[ErrorCode2.InvalidRequest = -32600] = "InvalidRequest", ErrorCode2[ErrorCode2.MethodNotFound = -32601] = "MethodNotFound", ErrorCode2[ErrorCode2.InvalidParams = -32602] = "InvalidParams", ErrorCode2[ErrorCode2.InternalError = -32603] = "InternalError", ErrorCode2[ErrorCode2.UrlElicitationRequired = -32042] = "UrlElicitationRequired";
})(ErrorCode || (ErrorCode = {}));
var JSONRPCErrorResponseSchema = object2({
  jsonrpc: literal(JSONRPC_VERSION),
  id: RequestIdSchema.optional(),
  error: object2({
    /**
     * The error type that occurred.
     */
    code: number2().int(),
    /**
     * A short description of the error. The message SHOULD be limited to a concise single sentence.
     */
    message: string2(),
    /**
     * Additional information about the error. The value of this member is defined by the sender (e.g. detailed error information, nested errors etc.).
     */
    data: unknown().optional()
  })
}).strict();
var isJSONRPCErrorResponse = (value) => JSONRPCErrorResponseSchema.safeParse(value).success;
var JSONRPCMessageSchema = union([
  JSONRPCRequestSchema,
  JSONRPCNotificationSchema,
  JSONRPCResultResponseSchema,
  JSONRPCErrorResponseSchema
]), JSONRPCResponseSchema = union([JSONRPCResultResponseSchema, JSONRPCErrorResponseSchema]), EmptyResultSchema = ResultSchema.strict(), CancelledNotificationParamsSchema = NotificationsParamsSchema.extend({
  /**
   * The ID of the request to cancel.
   *
   * This MUST correspond to the ID of a request previously issued in the same direction.
   */
  requestId: RequestIdSchema.optional(),
  /**
   * An optional string describing the reason for the cancellation. This MAY be logged or presented to the user.
   */
  reason: string2().optional()
}), CancelledNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/cancelled"),
  params: CancelledNotificationParamsSchema
}), IconSchema = object2({
  /**
   * URL or data URI for the icon.
   */
  src: string2(),
  /**
   * Optional MIME type for the icon.
   */
  mimeType: string2().optional(),
  /**
   * Optional array of strings that specify sizes at which the icon can be used.
   * Each string should be in WxH format (e.g., `"48x48"`, `"96x96"`) or `"any"` for scalable formats like SVG.
   *
   * If not provided, the client should assume that the icon can be used at any size.
   */
  sizes: array(string2()).optional(),
  /**
   * Optional specifier for the theme this icon is designed for. `light` indicates
   * the icon is designed to be used with a light background, and `dark` indicates
   * the icon is designed to be used with a dark background.
   *
   * If not provided, the client should assume the icon can be used with any theme.
   */
  theme: _enum(["light", "dark"]).optional()
}), IconsSchema = object2({
  /**
   * Optional set of sized icons that the client can display in a user interface.
   *
   * Clients that support rendering icons MUST support at least the following MIME types:
   * - `image/png` - PNG images (safe, universal compatibility)
   * - `image/jpeg` (and `image/jpg`) - JPEG images (safe, universal compatibility)
   *
   * Clients that support rendering icons SHOULD also support:
   * - `image/svg+xml` - SVG images (scalable but requires security precautions)
   * - `image/webp` - WebP images (modern, efficient format)
   */
  icons: array(IconSchema).optional()
}), BaseMetadataSchema = object2({
  /** Intended for programmatic or logical use, but used as a display name in past specs or fallback */
  name: string2(),
  /**
   * Intended for UI and end-user contexts — optimized to be human-readable and easily understood,
   * even by those unfamiliar with domain-specific terminology.
   *
   * If not provided, the name should be used for display (except for Tool,
   * where `annotations.title` should be given precedence over using `name`,
   * if present).
   */
  title: string2().optional()
}), ImplementationSchema = BaseMetadataSchema.extend({
  ...BaseMetadataSchema.shape,
  ...IconsSchema.shape,
  version: string2(),
  /**
   * An optional URL of the website for this implementation.
   */
  websiteUrl: string2().optional(),
  /**
   * An optional human-readable description of what this implementation does.
   *
   * This can be used by clients or servers to provide context about their purpose
   * and capabilities. For example, a server might describe the types of resources
   * or tools it provides, while a client might describe its intended use case.
   */
  description: string2().optional()
}), FormElicitationCapabilitySchema = intersection(object2({
  applyDefaults: boolean2().optional()
}), record(string2(), unknown())), ElicitationCapabilitySchema = preprocess((value) => value && typeof value == "object" && !Array.isArray(value) && Object.keys(value).length === 0 ? { form: {} } : value, intersection(object2({
  form: FormElicitationCapabilitySchema.optional(),
  url: AssertObjectSchema.optional()
}), record(string2(), unknown()).optional())), ClientTasksCapabilitySchema = looseObject({
  /**
   * Present if the client supports listing tasks.
   */
  list: AssertObjectSchema.optional(),
  /**
   * Present if the client supports cancelling tasks.
   */
  cancel: AssertObjectSchema.optional(),
  /**
   * Capabilities for task creation on specific request types.
   */
  requests: looseObject({
    /**
     * Task support for sampling requests.
     */
    sampling: looseObject({
      createMessage: AssertObjectSchema.optional()
    }).optional(),
    /**
     * Task support for elicitation requests.
     */
    elicitation: looseObject({
      create: AssertObjectSchema.optional()
    }).optional()
  }).optional()
}), ServerTasksCapabilitySchema = looseObject({
  /**
   * Present if the server supports listing tasks.
   */
  list: AssertObjectSchema.optional(),
  /**
   * Present if the server supports cancelling tasks.
   */
  cancel: AssertObjectSchema.optional(),
  /**
   * Capabilities for task creation on specific request types.
   */
  requests: looseObject({
    /**
     * Task support for tool requests.
     */
    tools: looseObject({
      call: AssertObjectSchema.optional()
    }).optional()
  }).optional()
}), ClientCapabilitiesSchema = object2({
  /**
   * Experimental, non-standard capabilities that the client supports.
   */
  experimental: record(string2(), AssertObjectSchema).optional(),
  /**
   * Present if the client supports sampling from an LLM.
   */
  sampling: object2({
    /**
     * Present if the client supports context inclusion via includeContext parameter.
     * If not declared, servers SHOULD only use `includeContext: "none"` (or omit it).
     */
    context: AssertObjectSchema.optional(),
    /**
     * Present if the client supports tool use via tools and toolChoice parameters.
     */
    tools: AssertObjectSchema.optional()
  }).optional(),
  /**
   * Present if the client supports eliciting user input.
   */
  elicitation: ElicitationCapabilitySchema.optional(),
  /**
   * Present if the client supports listing roots.
   */
  roots: object2({
    /**
     * Whether the client supports issuing notifications for changes to the roots list.
     */
    listChanged: boolean2().optional()
  }).optional(),
  /**
   * Present if the client supports task creation.
   */
  tasks: ClientTasksCapabilitySchema.optional(),
  /**
   * Extensions that the client supports. Keys are extension identifiers (vendor-prefix/extension-name).
   */
  extensions: record(string2(), AssertObjectSchema).optional()
}), InitializeRequestParamsSchema = BaseRequestParamsSchema.extend({
  /**
   * The latest version of the Model Context Protocol that the client supports. The client MAY decide to support older versions as well.
   */
  protocolVersion: string2(),
  capabilities: ClientCapabilitiesSchema,
  clientInfo: ImplementationSchema
}), InitializeRequestSchema = RequestSchema.extend({
  method: literal("initialize"),
  params: InitializeRequestParamsSchema
}), isInitializeRequest = (value) => InitializeRequestSchema.safeParse(value).success, ServerCapabilitiesSchema = object2({
  /**
   * Experimental, non-standard capabilities that the server supports.
   */
  experimental: record(string2(), AssertObjectSchema).optional(),
  /**
   * Present if the server supports sending log messages to the client.
   */
  logging: AssertObjectSchema.optional(),
  /**
   * Present if the server supports sending completions to the client.
   */
  completions: AssertObjectSchema.optional(),
  /**
   * Present if the server offers any prompt templates.
   */
  prompts: object2({
    /**
     * Whether this server supports issuing notifications for changes to the prompt list.
     */
    listChanged: boolean2().optional()
  }).optional(),
  /**
   * Present if the server offers any resources to read.
   */
  resources: object2({
    /**
     * Whether this server supports clients subscribing to resource updates.
     */
    subscribe: boolean2().optional(),
    /**
     * Whether this server supports issuing notifications for changes to the resource list.
     */
    listChanged: boolean2().optional()
  }).optional(),
  /**
   * Present if the server offers any tools to call.
   */
  tools: object2({
    /**
     * Whether this server supports issuing notifications for changes to the tool list.
     */
    listChanged: boolean2().optional()
  }).optional(),
  /**
   * Present if the server supports task creation.
   */
  tasks: ServerTasksCapabilitySchema.optional(),
  /**
   * Extensions that the server supports. Keys are extension identifiers (vendor-prefix/extension-name).
   */
  extensions: record(string2(), AssertObjectSchema).optional()
}), InitializeResultSchema = ResultSchema.extend({
  /**
   * The version of the Model Context Protocol that the server wants to use. This may not match the version that the client requested. If the client cannot support this version, it MUST disconnect.
   */
  protocolVersion: string2(),
  capabilities: ServerCapabilitiesSchema,
  serverInfo: ImplementationSchema,
  /**
   * Instructions describing how to use the server and its features.
   *
   * This can be used by clients to improve the LLM's understanding of available tools, resources, etc. It can be thought of like a "hint" to the model. For example, this information MAY be added to the system prompt.
   */
  instructions: string2().optional()
}), InitializedNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/initialized"),
  params: NotificationsParamsSchema.optional()
});
var PingRequestSchema = RequestSchema.extend({
  method: literal("ping"),
  params: BaseRequestParamsSchema.optional()
}), ProgressSchema = object2({
  /**
   * The progress thus far. This should increase every time progress is made, even if the total is unknown.
   */
  progress: number2(),
  /**
   * Total number of items to process (or total progress required), if known.
   */
  total: optional(number2()),
  /**
   * An optional message describing the current progress.
   */
  message: optional(string2())
}), ProgressNotificationParamsSchema = object2({
  ...NotificationsParamsSchema.shape,
  ...ProgressSchema.shape,
  /**
   * The progress token which was given in the initial request, used to associate this notification with the request that is proceeding.
   */
  progressToken: ProgressTokenSchema
}), ProgressNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/progress"),
  params: ProgressNotificationParamsSchema
}), PaginatedRequestParamsSchema = BaseRequestParamsSchema.extend({
  /**
   * An opaque token representing the current pagination position.
   * If provided, the server should return results starting after this cursor.
   */
  cursor: CursorSchema.optional()
}), PaginatedRequestSchema = RequestSchema.extend({
  params: PaginatedRequestParamsSchema.optional()
}), PaginatedResultSchema = ResultSchema.extend({
  /**
   * An opaque token representing the pagination position after the last returned result.
   * If present, there may be more results available.
   */
  nextCursor: CursorSchema.optional()
}), TaskStatusSchema = _enum(["working", "input_required", "completed", "failed", "cancelled"]), TaskSchema = object2({
  taskId: string2(),
  status: TaskStatusSchema,
  /**
   * Time in milliseconds to keep task results available after completion.
   * If null, the task has unlimited lifetime until manually cleaned up.
   */
  ttl: union([number2(), _null3()]),
  /**
   * ISO 8601 timestamp when the task was created.
   */
  createdAt: string2(),
  /**
   * ISO 8601 timestamp when the task was last updated.
   */
  lastUpdatedAt: string2(),
  pollInterval: optional(number2()),
  /**
   * Optional diagnostic message for failed tasks or other status information.
   */
  statusMessage: optional(string2())
}), CreateTaskResultSchema = ResultSchema.extend({
  task: TaskSchema
}), TaskStatusNotificationParamsSchema = NotificationsParamsSchema.merge(TaskSchema), TaskStatusNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/tasks/status"),
  params: TaskStatusNotificationParamsSchema
}), GetTaskRequestSchema = RequestSchema.extend({
  method: literal("tasks/get"),
  params: BaseRequestParamsSchema.extend({
    taskId: string2()
  })
}), GetTaskResultSchema = ResultSchema.merge(TaskSchema), GetTaskPayloadRequestSchema = RequestSchema.extend({
  method: literal("tasks/result"),
  params: BaseRequestParamsSchema.extend({
    taskId: string2()
  })
}), GetTaskPayloadResultSchema = ResultSchema.loose(), ListTasksRequestSchema = PaginatedRequestSchema.extend({
  method: literal("tasks/list")
}), ListTasksResultSchema = PaginatedResultSchema.extend({
  tasks: array(TaskSchema)
}), CancelTaskRequestSchema = RequestSchema.extend({
  method: literal("tasks/cancel"),
  params: BaseRequestParamsSchema.extend({
    taskId: string2()
  })
}), CancelTaskResultSchema = ResultSchema.merge(TaskSchema), ResourceContentsSchema = object2({
  /**
   * The URI of this resource.
   */
  uri: string2(),
  /**
   * The MIME type of this resource, if known.
   */
  mimeType: optional(string2()),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), TextResourceContentsSchema = ResourceContentsSchema.extend({
  /**
   * The text of the item. This must only be set if the item can actually be represented as text (not binary data).
   */
  text: string2()
}), Base64Schema = string2().refine((val) => {
  try {
    return atob(val), !0;
  } catch {
    return !1;
  }
}, { message: "Invalid Base64 string" }), BlobResourceContentsSchema = ResourceContentsSchema.extend({
  /**
   * A base64-encoded string representing the binary data of the item.
   */
  blob: Base64Schema
}), RoleSchema = _enum(["user", "assistant"]), AnnotationsSchema = object2({
  /**
   * Intended audience(s) for the resource.
   */
  audience: array(RoleSchema).optional(),
  /**
   * Importance hint for the resource, from 0 (least) to 1 (most).
   */
  priority: number2().min(0).max(1).optional(),
  /**
   * ISO 8601 timestamp for the most recent modification.
   */
  lastModified: iso_exports2.datetime({ offset: !0 }).optional()
}), ResourceSchema = object2({
  ...BaseMetadataSchema.shape,
  ...IconsSchema.shape,
  /**
   * The URI of this resource.
   */
  uri: string2(),
  /**
   * A description of what this resource represents.
   *
   * This can be used by clients to improve the LLM's understanding of available resources. It can be thought of like a "hint" to the model.
   */
  description: optional(string2()),
  /**
   * The MIME type of this resource, if known.
   */
  mimeType: optional(string2()),
  /**
   * The size of the raw resource content, in bytes (i.e., before base64 encoding or any tokenization), if known.
   *
   * This can be used by Hosts to display file sizes and estimate context window usage.
   */
  size: optional(number2()),
  /**
   * Optional annotations for the client.
   */
  annotations: AnnotationsSchema.optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: optional(looseObject({}))
}), ResourceTemplateSchema = object2({
  ...BaseMetadataSchema.shape,
  ...IconsSchema.shape,
  /**
   * A URI template (according to RFC 6570) that can be used to construct resource URIs.
   */
  uriTemplate: string2(),
  /**
   * A description of what this template is for.
   *
   * This can be used by clients to improve the LLM's understanding of available resources. It can be thought of like a "hint" to the model.
   */
  description: optional(string2()),
  /**
   * The MIME type for all resources that match this template. This should only be included if all resources matching this template have the same type.
   */
  mimeType: optional(string2()),
  /**
   * Optional annotations for the client.
   */
  annotations: AnnotationsSchema.optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: optional(looseObject({}))
}), ListResourcesRequestSchema = PaginatedRequestSchema.extend({
  method: literal("resources/list")
}), ListResourcesResultSchema = PaginatedResultSchema.extend({
  resources: array(ResourceSchema)
}), ListResourceTemplatesRequestSchema = PaginatedRequestSchema.extend({
  method: literal("resources/templates/list")
}), ListResourceTemplatesResultSchema = PaginatedResultSchema.extend({
  resourceTemplates: array(ResourceTemplateSchema)
}), ResourceRequestParamsSchema = BaseRequestParamsSchema.extend({
  /**
   * The URI of the resource to read. The URI can use any protocol; it is up to the server how to interpret it.
   *
   * @format uri
   */
  uri: string2()
}), ReadResourceRequestParamsSchema = ResourceRequestParamsSchema, ReadResourceRequestSchema = RequestSchema.extend({
  method: literal("resources/read"),
  params: ReadResourceRequestParamsSchema
}), ReadResourceResultSchema = ResultSchema.extend({
  contents: array(union([TextResourceContentsSchema, BlobResourceContentsSchema]))
}), ResourceListChangedNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/resources/list_changed"),
  params: NotificationsParamsSchema.optional()
}), SubscribeRequestParamsSchema = ResourceRequestParamsSchema, SubscribeRequestSchema = RequestSchema.extend({
  method: literal("resources/subscribe"),
  params: SubscribeRequestParamsSchema
}), UnsubscribeRequestParamsSchema = ResourceRequestParamsSchema, UnsubscribeRequestSchema = RequestSchema.extend({
  method: literal("resources/unsubscribe"),
  params: UnsubscribeRequestParamsSchema
}), ResourceUpdatedNotificationParamsSchema = NotificationsParamsSchema.extend({
  /**
   * The URI of the resource that has been updated. This might be a sub-resource of the one that the client actually subscribed to.
   */
  uri: string2()
}), ResourceUpdatedNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/resources/updated"),
  params: ResourceUpdatedNotificationParamsSchema
}), PromptArgumentSchema = object2({
  /**
   * The name of the argument.
   */
  name: string2(),
  /**
   * A human-readable description of the argument.
   */
  description: optional(string2()),
  /**
   * Whether this argument must be provided.
   */
  required: optional(boolean2())
}), PromptSchema = object2({
  ...BaseMetadataSchema.shape,
  ...IconsSchema.shape,
  /**
   * An optional description of what this prompt provides
   */
  description: optional(string2()),
  /**
   * A list of arguments to use for templating the prompt.
   */
  arguments: optional(array(PromptArgumentSchema)),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: optional(looseObject({}))
}), ListPromptsRequestSchema = PaginatedRequestSchema.extend({
  method: literal("prompts/list")
}), ListPromptsResultSchema = PaginatedResultSchema.extend({
  prompts: array(PromptSchema)
}), GetPromptRequestParamsSchema = BaseRequestParamsSchema.extend({
  /**
   * The name of the prompt or prompt template.
   */
  name: string2(),
  /**
   * Arguments to use for templating the prompt.
   */
  arguments: record(string2(), string2()).optional()
}), GetPromptRequestSchema = RequestSchema.extend({
  method: literal("prompts/get"),
  params: GetPromptRequestParamsSchema
}), TextContentSchema = object2({
  type: literal("text"),
  /**
   * The text content of the message.
   */
  text: string2(),
  /**
   * Optional annotations for the client.
   */
  annotations: AnnotationsSchema.optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), ImageContentSchema = object2({
  type: literal("image"),
  /**
   * The base64-encoded image data.
   */
  data: Base64Schema,
  /**
   * The MIME type of the image. Different providers may support different image types.
   */
  mimeType: string2(),
  /**
   * Optional annotations for the client.
   */
  annotations: AnnotationsSchema.optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), AudioContentSchema = object2({
  type: literal("audio"),
  /**
   * The base64-encoded audio data.
   */
  data: Base64Schema,
  /**
   * The MIME type of the audio. Different providers may support different audio types.
   */
  mimeType: string2(),
  /**
   * Optional annotations for the client.
   */
  annotations: AnnotationsSchema.optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), ToolUseContentSchema = object2({
  type: literal("tool_use"),
  /**
   * The name of the tool to invoke.
   * Must match a tool name from the request's tools array.
   */
  name: string2(),
  /**
   * Unique identifier for this tool call.
   * Used to correlate with ToolResultContent in subsequent messages.
   */
  id: string2(),
  /**
   * Arguments to pass to the tool.
   * Must conform to the tool's inputSchema.
   */
  input: record(string2(), unknown()),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), EmbeddedResourceSchema = object2({
  type: literal("resource"),
  resource: union([TextResourceContentsSchema, BlobResourceContentsSchema]),
  /**
   * Optional annotations for the client.
   */
  annotations: AnnotationsSchema.optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), ResourceLinkSchema = ResourceSchema.extend({
  type: literal("resource_link")
}), ContentBlockSchema = union([
  TextContentSchema,
  ImageContentSchema,
  AudioContentSchema,
  ResourceLinkSchema,
  EmbeddedResourceSchema
]), PromptMessageSchema = object2({
  role: RoleSchema,
  content: ContentBlockSchema
}), GetPromptResultSchema = ResultSchema.extend({
  /**
   * An optional description for the prompt.
   */
  description: string2().optional(),
  messages: array(PromptMessageSchema)
}), PromptListChangedNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/prompts/list_changed"),
  params: NotificationsParamsSchema.optional()
}), ToolAnnotationsSchema = object2({
  /**
   * A human-readable title for the tool.
   */
  title: string2().optional(),
  /**
   * If true, the tool does not modify its environment.
   *
   * Default: false
   */
  readOnlyHint: boolean2().optional(),
  /**
   * If true, the tool may perform destructive updates to its environment.
   * If false, the tool performs only additive updates.
   *
   * (This property is meaningful only when `readOnlyHint == false`)
   *
   * Default: true
   */
  destructiveHint: boolean2().optional(),
  /**
   * If true, calling the tool repeatedly with the same arguments
   * will have no additional effect on the its environment.
   *
   * (This property is meaningful only when `readOnlyHint == false`)
   *
   * Default: false
   */
  idempotentHint: boolean2().optional(),
  /**
   * If true, this tool may interact with an "open world" of external
   * entities. If false, the tool's domain of interaction is closed.
   * For example, the world of a web search tool is open, whereas that
   * of a memory tool is not.
   *
   * Default: true
   */
  openWorldHint: boolean2().optional()
}), ToolExecutionSchema = object2({
  /**
   * Indicates the tool's preference for task-augmented execution.
   * - "required": Clients MUST invoke the tool as a task
   * - "optional": Clients MAY invoke the tool as a task or normal request
   * - "forbidden": Clients MUST NOT attempt to invoke the tool as a task
   *
   * If not present, defaults to "forbidden".
   */
  taskSupport: _enum(["required", "optional", "forbidden"]).optional()
}), ToolSchema = object2({
  ...BaseMetadataSchema.shape,
  ...IconsSchema.shape,
  /**
   * A human-readable description of the tool.
   */
  description: string2().optional(),
  /**
   * A JSON Schema 2020-12 object defining the expected parameters for the tool.
   * Must have type: 'object' at the root level per MCP spec.
   */
  inputSchema: object2({
    type: literal("object"),
    properties: record(string2(), AssertObjectSchema).optional(),
    required: array(string2()).optional()
  }).catchall(unknown()),
  /**
   * An optional JSON Schema 2020-12 object defining the structure of the tool's output
   * returned in the structuredContent field of a CallToolResult.
   * Must have type: 'object' at the root level per MCP spec.
   */
  outputSchema: object2({
    type: literal("object"),
    properties: record(string2(), AssertObjectSchema).optional(),
    required: array(string2()).optional()
  }).catchall(unknown()).optional(),
  /**
   * Optional additional tool information.
   */
  annotations: ToolAnnotationsSchema.optional(),
  /**
   * Execution-related properties for this tool.
   */
  execution: ToolExecutionSchema.optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), ListToolsRequestSchema = PaginatedRequestSchema.extend({
  method: literal("tools/list")
}), ListToolsResultSchema = PaginatedResultSchema.extend({
  tools: array(ToolSchema)
}), CallToolResultSchema = ResultSchema.extend({
  /**
   * A list of content objects that represent the result of the tool call.
   *
   * If the Tool does not define an outputSchema, this field MUST be present in the result.
   * For backwards compatibility, this field is always present, but it may be empty.
   */
  content: array(ContentBlockSchema).default([]),
  /**
   * An object containing structured tool output.
   *
   * If the Tool defines an outputSchema, this field MUST be present in the result, and contain a JSON object that matches the schema.
   */
  structuredContent: record(string2(), unknown()).optional(),
  /**
   * Whether the tool call ended in an error.
   *
   * If not set, this is assumed to be false (the call was successful).
   *
   * Any errors that originate from the tool SHOULD be reported inside the result
   * object, with `isError` set to true, _not_ as an MCP protocol-level error
   * response. Otherwise, the LLM would not be able to see that an error occurred
   * and self-correct.
   *
   * However, any errors in _finding_ the tool, an error indicating that the
   * server does not support tool calls, or any other exceptional conditions,
   * should be reported as an MCP error response.
   */
  isError: boolean2().optional()
}), CompatibilityCallToolResultSchema = CallToolResultSchema.or(ResultSchema.extend({
  toolResult: unknown()
})), CallToolRequestParamsSchema = TaskAugmentedRequestParamsSchema.extend({
  /**
   * The name of the tool to call.
   */
  name: string2(),
  /**
   * Arguments to pass to the tool.
   */
  arguments: record(string2(), unknown()).optional()
}), CallToolRequestSchema = RequestSchema.extend({
  method: literal("tools/call"),
  params: CallToolRequestParamsSchema
}), ToolListChangedNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/tools/list_changed"),
  params: NotificationsParamsSchema.optional()
}), ListChangedOptionsBaseSchema = object2({
  /**
   * If true, the list will be refreshed automatically when a list changed notification is received.
   * The callback will be called with the updated list.
   *
   * If false, the callback will be called with null items, allowing manual refresh.
   *
   * @default true
   */
  autoRefresh: boolean2().default(!0),
  /**
   * Debounce time in milliseconds for list changed notification processing.
   *
   * Multiple notifications received within this timeframe will only trigger one refresh.
   * Set to 0 to disable debouncing.
   *
   * @default 300
   */
  debounceMs: number2().int().nonnegative().default(300)
}), LoggingLevelSchema = _enum(["debug", "info", "notice", "warning", "error", "critical", "alert", "emergency"]), SetLevelRequestParamsSchema = BaseRequestParamsSchema.extend({
  /**
   * The level of logging that the client wants to receive from the server. The server should send all logs at this level and higher (i.e., more severe) to the client as notifications/logging/message.
   */
  level: LoggingLevelSchema
}), SetLevelRequestSchema = RequestSchema.extend({
  method: literal("logging/setLevel"),
  params: SetLevelRequestParamsSchema
}), LoggingMessageNotificationParamsSchema = NotificationsParamsSchema.extend({
  /**
   * The severity of this log message.
   */
  level: LoggingLevelSchema,
  /**
   * An optional name of the logger issuing this message.
   */
  logger: string2().optional(),
  /**
   * The data to be logged, such as a string message or an object. Any JSON serializable type is allowed here.
   */
  data: unknown()
}), LoggingMessageNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/message"),
  params: LoggingMessageNotificationParamsSchema
}), ModelHintSchema = object2({
  /**
   * A hint for a model name.
   */
  name: string2().optional()
}), ModelPreferencesSchema = object2({
  /**
   * Optional hints to use for model selection.
   */
  hints: array(ModelHintSchema).optional(),
  /**
   * How much to prioritize cost when selecting a model.
   */
  costPriority: number2().min(0).max(1).optional(),
  /**
   * How much to prioritize sampling speed (latency) when selecting a model.
   */
  speedPriority: number2().min(0).max(1).optional(),
  /**
   * How much to prioritize intelligence and capabilities when selecting a model.
   */
  intelligencePriority: number2().min(0).max(1).optional()
}), ToolChoiceSchema = object2({
  /**
   * Controls when tools are used:
   * - "auto": Model decides whether to use tools (default)
   * - "required": Model MUST use at least one tool before completing
   * - "none": Model MUST NOT use any tools
   */
  mode: _enum(["auto", "required", "none"]).optional()
}), ToolResultContentSchema = object2({
  type: literal("tool_result"),
  toolUseId: string2().describe("The unique identifier for the corresponding tool call."),
  content: array(ContentBlockSchema).default([]),
  structuredContent: object2({}).loose().optional(),
  isError: boolean2().optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), SamplingContentSchema = discriminatedUnion("type", [TextContentSchema, ImageContentSchema, AudioContentSchema]), SamplingMessageContentBlockSchema = discriminatedUnion("type", [
  TextContentSchema,
  ImageContentSchema,
  AudioContentSchema,
  ToolUseContentSchema,
  ToolResultContentSchema
]), SamplingMessageSchema = object2({
  role: RoleSchema,
  content: union([SamplingMessageContentBlockSchema, array(SamplingMessageContentBlockSchema)]),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), CreateMessageRequestParamsSchema = TaskAugmentedRequestParamsSchema.extend({
  messages: array(SamplingMessageSchema),
  /**
   * The server's preferences for which model to select. The client MAY modify or omit this request.
   */
  modelPreferences: ModelPreferencesSchema.optional(),
  /**
   * An optional system prompt the server wants to use for sampling. The client MAY modify or omit this prompt.
   */
  systemPrompt: string2().optional(),
  /**
   * A request to include context from one or more MCP servers (including the caller), to be attached to the prompt.
   * The client MAY ignore this request.
   *
   * Default is "none". Values "thisServer" and "allServers" are soft-deprecated. Servers SHOULD only use these values if the client
   * declares ClientCapabilities.sampling.context. These values may be removed in future spec releases.
   */
  includeContext: _enum(["none", "thisServer", "allServers"]).optional(),
  temperature: number2().optional(),
  /**
   * The requested maximum number of tokens to sample (to prevent runaway completions).
   *
   * The client MAY choose to sample fewer tokens than the requested maximum.
   */
  maxTokens: number2().int(),
  stopSequences: array(string2()).optional(),
  /**
   * Optional metadata to pass through to the LLM provider. The format of this metadata is provider-specific.
   */
  metadata: AssertObjectSchema.optional(),
  /**
   * Tools that the model may use during generation.
   * The client MUST return an error if this field is provided but ClientCapabilities.sampling.tools is not declared.
   */
  tools: array(ToolSchema).optional(),
  /**
   * Controls how the model uses tools.
   * The client MUST return an error if this field is provided but ClientCapabilities.sampling.tools is not declared.
   * Default is `{ mode: "auto" }`.
   */
  toolChoice: ToolChoiceSchema.optional()
}), CreateMessageRequestSchema = RequestSchema.extend({
  method: literal("sampling/createMessage"),
  params: CreateMessageRequestParamsSchema
}), CreateMessageResultSchema = ResultSchema.extend({
  /**
   * The name of the model that generated the message.
   */
  model: string2(),
  /**
   * The reason why sampling stopped, if known.
   *
   * Standard values:
   * - "endTurn": Natural end of the assistant's turn
   * - "stopSequence": A stop sequence was encountered
   * - "maxTokens": Maximum token limit was reached
   *
   * This field is an open string to allow for provider-specific stop reasons.
   */
  stopReason: optional(_enum(["endTurn", "stopSequence", "maxTokens"]).or(string2())),
  role: RoleSchema,
  /**
   * Response content. Single content block (text, image, or audio).
   */
  content: SamplingContentSchema
}), CreateMessageResultWithToolsSchema = ResultSchema.extend({
  /**
   * The name of the model that generated the message.
   */
  model: string2(),
  /**
   * The reason why sampling stopped, if known.
   *
   * Standard values:
   * - "endTurn": Natural end of the assistant's turn
   * - "stopSequence": A stop sequence was encountered
   * - "maxTokens": Maximum token limit was reached
   * - "toolUse": The model wants to use one or more tools
   *
   * This field is an open string to allow for provider-specific stop reasons.
   */
  stopReason: optional(_enum(["endTurn", "stopSequence", "maxTokens", "toolUse"]).or(string2())),
  role: RoleSchema,
  /**
   * Response content. May be a single block or array. May include ToolUseContent if stopReason is "toolUse".
   */
  content: union([SamplingMessageContentBlockSchema, array(SamplingMessageContentBlockSchema)])
}), BooleanSchemaSchema = object2({
  type: literal("boolean"),
  title: string2().optional(),
  description: string2().optional(),
  default: boolean2().optional()
}), StringSchemaSchema = object2({
  type: literal("string"),
  title: string2().optional(),
  description: string2().optional(),
  minLength: number2().optional(),
  maxLength: number2().optional(),
  format: _enum(["email", "uri", "date", "date-time"]).optional(),
  default: string2().optional()
}), NumberSchemaSchema = object2({
  type: _enum(["number", "integer"]),
  title: string2().optional(),
  description: string2().optional(),
  minimum: number2().optional(),
  maximum: number2().optional(),
  default: number2().optional()
}), UntitledSingleSelectEnumSchemaSchema = object2({
  type: literal("string"),
  title: string2().optional(),
  description: string2().optional(),
  enum: array(string2()),
  default: string2().optional()
}), TitledSingleSelectEnumSchemaSchema = object2({
  type: literal("string"),
  title: string2().optional(),
  description: string2().optional(),
  oneOf: array(object2({
    const: string2(),
    title: string2()
  })),
  default: string2().optional()
}), LegacyTitledEnumSchemaSchema = object2({
  type: literal("string"),
  title: string2().optional(),
  description: string2().optional(),
  enum: array(string2()),
  enumNames: array(string2()).optional(),
  default: string2().optional()
}), SingleSelectEnumSchemaSchema = union([UntitledSingleSelectEnumSchemaSchema, TitledSingleSelectEnumSchemaSchema]), UntitledMultiSelectEnumSchemaSchema = object2({
  type: literal("array"),
  title: string2().optional(),
  description: string2().optional(),
  minItems: number2().optional(),
  maxItems: number2().optional(),
  items: object2({
    type: literal("string"),
    enum: array(string2())
  }),
  default: array(string2()).optional()
}), TitledMultiSelectEnumSchemaSchema = object2({
  type: literal("array"),
  title: string2().optional(),
  description: string2().optional(),
  minItems: number2().optional(),
  maxItems: number2().optional(),
  items: object2({
    anyOf: array(object2({
      const: string2(),
      title: string2()
    }))
  }),
  default: array(string2()).optional()
}), MultiSelectEnumSchemaSchema = union([UntitledMultiSelectEnumSchemaSchema, TitledMultiSelectEnumSchemaSchema]), EnumSchemaSchema = union([LegacyTitledEnumSchemaSchema, SingleSelectEnumSchemaSchema, MultiSelectEnumSchemaSchema]), PrimitiveSchemaDefinitionSchema = union([EnumSchemaSchema, BooleanSchemaSchema, StringSchemaSchema, NumberSchemaSchema]), ElicitRequestFormParamsSchema = TaskAugmentedRequestParamsSchema.extend({
  /**
   * The elicitation mode.
   *
   * Optional for backward compatibility. Clients MUST treat missing mode as "form".
   */
  mode: literal("form").optional(),
  /**
   * The message to present to the user describing what information is being requested.
   */
  message: string2(),
  /**
   * A restricted subset of JSON Schema.
   * Only top-level properties are allowed, without nesting.
   */
  requestedSchema: object2({
    type: literal("object"),
    properties: record(string2(), PrimitiveSchemaDefinitionSchema),
    required: array(string2()).optional()
  })
}), ElicitRequestURLParamsSchema = TaskAugmentedRequestParamsSchema.extend({
  /**
   * The elicitation mode.
   */
  mode: literal("url"),
  /**
   * The message to present to the user explaining why the interaction is needed.
   */
  message: string2(),
  /**
   * The ID of the elicitation, which must be unique within the context of the server.
   * The client MUST treat this ID as an opaque value.
   */
  elicitationId: string2(),
  /**
   * The URL that the user should navigate to.
   */
  url: string2().url()
}), ElicitRequestParamsSchema = union([ElicitRequestFormParamsSchema, ElicitRequestURLParamsSchema]), ElicitRequestSchema = RequestSchema.extend({
  method: literal("elicitation/create"),
  params: ElicitRequestParamsSchema
}), ElicitationCompleteNotificationParamsSchema = NotificationsParamsSchema.extend({
  /**
   * The ID of the elicitation that completed.
   */
  elicitationId: string2()
}), ElicitationCompleteNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/elicitation/complete"),
  params: ElicitationCompleteNotificationParamsSchema
}), ElicitResultSchema = ResultSchema.extend({
  /**
   * The user action in response to the elicitation.
   * - "accept": User submitted the form/confirmed the action
   * - "decline": User explicitly decline the action
   * - "cancel": User dismissed without making an explicit choice
   */
  action: _enum(["accept", "decline", "cancel"]),
  /**
   * The submitted form data, only present when action is "accept".
   * Contains values matching the requested schema.
   * Per MCP spec, content is "typically omitted" for decline/cancel actions.
   * We normalize null to undefined for leniency while maintaining type compatibility.
   */
  content: preprocess((val) => val === null ? void 0 : val, record(string2(), union([string2(), number2(), boolean2(), array(string2())])).optional())
}), ResourceTemplateReferenceSchema = object2({
  type: literal("ref/resource"),
  /**
   * The URI or URI template of the resource.
   */
  uri: string2()
});
var PromptReferenceSchema = object2({
  type: literal("ref/prompt"),
  /**
   * The name of the prompt or prompt template
   */
  name: string2()
}), CompleteRequestParamsSchema = BaseRequestParamsSchema.extend({
  ref: union([PromptReferenceSchema, ResourceTemplateReferenceSchema]),
  /**
   * The argument's information
   */
  argument: object2({
    /**
     * The name of the argument
     */
    name: string2(),
    /**
     * The value of the argument to use for completion matching.
     */
    value: string2()
  }),
  context: object2({
    /**
     * Previously-resolved variables in a URI template or prompt.
     */
    arguments: record(string2(), string2()).optional()
  }).optional()
}), CompleteRequestSchema = RequestSchema.extend({
  method: literal("completion/complete"),
  params: CompleteRequestParamsSchema
});
function assertCompleteRequestPrompt(request) {
  if (request.params.ref.type !== "ref/prompt")
    throw new TypeError(`Expected CompleteRequestPrompt, but got ${request.params.ref.type}`);
}
function assertCompleteRequestResourceTemplate(request) {
  if (request.params.ref.type !== "ref/resource")
    throw new TypeError(`Expected CompleteRequestResourceTemplate, but got ${request.params.ref.type}`);
}
var CompleteResultSchema = ResultSchema.extend({
  completion: looseObject({
    /**
     * An array of completion values. Must not exceed 100 items.
     */
    values: array(string2()).max(100),
    /**
     * The total number of completion options available. This can exceed the number of values actually sent in the response.
     */
    total: optional(number2().int()),
    /**
     * Indicates whether there are additional completion options beyond those provided in the current response, even if the exact total is unknown.
     */
    hasMore: optional(boolean2())
  })
}), RootSchema = object2({
  /**
   * The URI identifying the root. This *must* start with file:// for now.
   */
  uri: string2().startsWith("file://"),
  /**
   * An optional name for the root.
   */
  name: string2().optional(),
  /**
   * See [MCP specification](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/47339c03c143bb4ec01a26e721a1b8fe66634ebe/docs/specification/draft/basic/index.mdx#general-fields)
   * for notes on _meta usage.
   */
  _meta: record(string2(), unknown()).optional()
}), ListRootsRequestSchema = RequestSchema.extend({
  method: literal("roots/list"),
  params: BaseRequestParamsSchema.optional()
}), ListRootsResultSchema = ResultSchema.extend({
  roots: array(RootSchema)
}), RootsListChangedNotificationSchema = NotificationSchema.extend({
  method: literal("notifications/roots/list_changed"),
  params: NotificationsParamsSchema.optional()
}), ClientRequestSchema = union([
  PingRequestSchema,
  InitializeRequestSchema,
  CompleteRequestSchema,
  SetLevelRequestSchema,
  GetPromptRequestSchema,
  ListPromptsRequestSchema,
  ListResourcesRequestSchema,
  ListResourceTemplatesRequestSchema,
  ReadResourceRequestSchema,
  SubscribeRequestSchema,
  UnsubscribeRequestSchema,
  CallToolRequestSchema,
  ListToolsRequestSchema,
  GetTaskRequestSchema,
  GetTaskPayloadRequestSchema,
  ListTasksRequestSchema,
  CancelTaskRequestSchema
]), ClientNotificationSchema = union([
  CancelledNotificationSchema,
  ProgressNotificationSchema,
  InitializedNotificationSchema,
  RootsListChangedNotificationSchema,
  TaskStatusNotificationSchema
]), ClientResultSchema = union([
  EmptyResultSchema,
  CreateMessageResultSchema,
  CreateMessageResultWithToolsSchema,
  ElicitResultSchema,
  ListRootsResultSchema,
  GetTaskResultSchema,
  ListTasksResultSchema,
  CreateTaskResultSchema
]), ServerRequestSchema = union([
  PingRequestSchema,
  CreateMessageRequestSchema,
  ElicitRequestSchema,
  ListRootsRequestSchema,
  GetTaskRequestSchema,
  GetTaskPayloadRequestSchema,
  ListTasksRequestSchema,
  CancelTaskRequestSchema
]), ServerNotificationSchema = union([
  CancelledNotificationSchema,
  ProgressNotificationSchema,
  LoggingMessageNotificationSchema,
  ResourceUpdatedNotificationSchema,
  ResourceListChangedNotificationSchema,
  ToolListChangedNotificationSchema,
  PromptListChangedNotificationSchema,
  TaskStatusNotificationSchema,
  ElicitationCompleteNotificationSchema
]), ServerResultSchema = union([
  EmptyResultSchema,
  InitializeResultSchema,
  CompleteResultSchema,
  GetPromptResultSchema,
  ListPromptsResultSchema,
  ListResourcesResultSchema,
  ListResourceTemplatesResultSchema,
  ReadResourceResultSchema,
  CallToolResultSchema,
  ListToolsResultSchema,
  GetTaskResultSchema,
  ListTasksResultSchema,
  CreateTaskResultSchema
]), McpError = class _McpError extends Error {
  constructor(code, message, data) {
    super(`MCP error ${code}: ${message}`), this.code = code, this.data = data, this.name = "McpError";
  }
  /**
   * Factory method to create the appropriate error type based on the error code and data
   */
  static fromError(code, message, data) {
    if (code === ErrorCode.UrlElicitationRequired && data) {
      let errorData = data;
      if (errorData.elicitations)
        return new UrlElicitationRequiredError(errorData.elicitations, message);
    }
    return new _McpError(code, message, data);
  }
}, UrlElicitationRequiredError = class extends McpError {
  constructor(elicitations, message = `URL elicitation${elicitations.length > 1 ? "s" : ""} required`) {
    super(ErrorCode.UrlElicitationRequired, message, {
      elicitations
    });
  }
  get elicitations() {
    return this.data?.elicitations ?? [];
  }
};

// node_modules/@modelcontextprotocol/sdk/dist/esm/experimental/tasks/interfaces.js
function isTerminal(status) {
  return status === "completed" || status === "failed" || status === "cancelled";
}

// node_modules/zod-to-json-schema/dist/esm/Options.js
var ignoreOverride = Symbol("Let zodToJsonSchema decide on which parser to use");
var defaultOptions = {
  name: void 0,
  $refStrategy: "root",
  basePath: ["#"],
  effectStrategy: "input",
  pipeStrategy: "all",
  dateStrategy: "format:date-time",
  mapStrategy: "entries",
  removeAdditionalStrategy: "passthrough",
  allowedAdditionalProperties: !0,
  rejectedAdditionalProperties: !1,
  definitionPath: "definitions",
  target: "jsonSchema7",
  strictUnions: !1,
  definitions: {},
  errorMessages: !1,
  markdownDescription: !1,
  patternStrategy: "escape",
  applyRegexFlags: !1,
  emailStrategy: "format:email",
  base64Strategy: "contentEncoding:base64",
  nameStrategy: "ref",
  openAiAnyTypeName: "OpenAiAnyType"
}, getDefaultOptions = (options) => typeof options == "string" ? {
  ...defaultOptions,
  name: options
} : {
  ...defaultOptions,
  ...options
};

// node_modules/zod-to-json-schema/dist/esm/Refs.js
var getRefs = (options) => {
  let _options = getDefaultOptions(options), currentPath = _options.name !== void 0 ? [..._options.basePath, _options.definitionPath, _options.name] : _options.basePath;
  return {
    ..._options,
    flags: { hasReferencedOpenAiAnyType: !1 },
    currentPath,
    propertyPath: void 0,
    seen: new Map(Object.entries(_options.definitions).map(([name, def]) => [
      def._def,
      {
        def: def._def,
        path: [..._options.basePath, _options.definitionPath, name],
        // Resolution of references will be forced even though seen, so it's ok that the schema is undefined here for now.
        jsonSchema: void 0
      }
    ]))
  };
};

// node_modules/zod-to-json-schema/dist/esm/errorMessages.js
function addErrorMessage(res, key, errorMessage, refs) {
  refs?.errorMessages && errorMessage && (res.errorMessage = {
    ...res.errorMessage,
    [key]: errorMessage
  });
}
function setResponseValueAndErrors(res, key, value, errorMessage, refs) {
  res[key] = value, addErrorMessage(res, key, errorMessage, refs);
}

// node_modules/zod-to-json-schema/dist/esm/getRelativePath.js
var getRelativePath = (pathA, pathB) => {
  let i = 0;
  for (; i < pathA.length && i < pathB.length && pathA[i] === pathB[i]; i++)
    ;
  return [(pathA.length - i).toString(), ...pathB.slice(i)].join("/");
};

// node_modules/zod-to-json-schema/dist/esm/parsers/any.js
function parseAnyDef(refs) {
  if (refs.target !== "openAi")
    return {};
  let anyDefinitionPath = [
    ...refs.basePath,
    refs.definitionPath,
    refs.openAiAnyTypeName
  ];
  return refs.flags.hasReferencedOpenAiAnyType = !0, {
    $ref: refs.$refStrategy === "relative" ? getRelativePath(anyDefinitionPath, refs.currentPath) : anyDefinitionPath.join("/")
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/array.js
function parseArrayDef(def, refs) {
  let res = {
    type: "array"
  };
  return def.type?._def && def.type?._def?.typeName !== ZodFirstPartyTypeKind.ZodAny && (res.items = parseDef(def.type._def, {
    ...refs,
    currentPath: [...refs.currentPath, "items"]
  })), def.minLength && setResponseValueAndErrors(res, "minItems", def.minLength.value, def.minLength.message, refs), def.maxLength && setResponseValueAndErrors(res, "maxItems", def.maxLength.value, def.maxLength.message, refs), def.exactLength && (setResponseValueAndErrors(res, "minItems", def.exactLength.value, def.exactLength.message, refs), setResponseValueAndErrors(res, "maxItems", def.exactLength.value, def.exactLength.message, refs)), res;
}

// node_modules/zod-to-json-schema/dist/esm/parsers/bigint.js
function parseBigintDef(def, refs) {
  let res = {
    type: "integer",
    format: "int64"
  };
  if (!def.checks)
    return res;
  for (let check2 of def.checks)
    switch (check2.kind) {
      case "min":
        refs.target === "jsonSchema7" ? check2.inclusive ? setResponseValueAndErrors(res, "minimum", check2.value, check2.message, refs) : setResponseValueAndErrors(res, "exclusiveMinimum", check2.value, check2.message, refs) : (check2.inclusive || (res.exclusiveMinimum = !0), setResponseValueAndErrors(res, "minimum", check2.value, check2.message, refs));
        break;
      case "max":
        refs.target === "jsonSchema7" ? check2.inclusive ? setResponseValueAndErrors(res, "maximum", check2.value, check2.message, refs) : setResponseValueAndErrors(res, "exclusiveMaximum", check2.value, check2.message, refs) : (check2.inclusive || (res.exclusiveMaximum = !0), setResponseValueAndErrors(res, "maximum", check2.value, check2.message, refs));
        break;
      case "multipleOf":
        setResponseValueAndErrors(res, "multipleOf", check2.value, check2.message, refs);
        break;
    }
  return res;
}

// node_modules/zod-to-json-schema/dist/esm/parsers/boolean.js
function parseBooleanDef() {
  return {
    type: "boolean"
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/branded.js
function parseBrandedDef(_def, refs) {
  return parseDef(_def.type._def, refs);
}

// node_modules/zod-to-json-schema/dist/esm/parsers/catch.js
var parseCatchDef = (def, refs) => parseDef(def.innerType._def, refs);

// node_modules/zod-to-json-schema/dist/esm/parsers/date.js
function parseDateDef(def, refs, overrideDateStrategy) {
  let strategy = overrideDateStrategy ?? refs.dateStrategy;
  if (Array.isArray(strategy))
    return {
      anyOf: strategy.map((item, i) => parseDateDef(def, refs, item))
    };
  switch (strategy) {
    case "string":
    case "format:date-time":
      return {
        type: "string",
        format: "date-time"
      };
    case "format:date":
      return {
        type: "string",
        format: "date"
      };
    case "integer":
      return integerDateParser(def, refs);
  }
}
var integerDateParser = (def, refs) => {
  let res = {
    type: "integer",
    format: "unix-time"
  };
  if (refs.target === "openApi3")
    return res;
  for (let check2 of def.checks)
    switch (check2.kind) {
      case "min":
        setResponseValueAndErrors(
          res,
          "minimum",
          check2.value,
          // This is in milliseconds
          check2.message,
          refs
        );
        break;
      case "max":
        setResponseValueAndErrors(
          res,
          "maximum",
          check2.value,
          // This is in milliseconds
          check2.message,
          refs
        );
        break;
    }
  return res;
};

// node_modules/zod-to-json-schema/dist/esm/parsers/default.js
function parseDefaultDef(_def, refs) {
  return {
    ...parseDef(_def.innerType._def, refs),
    default: _def.defaultValue()
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/effects.js
function parseEffectsDef(_def, refs) {
  return refs.effectStrategy === "input" ? parseDef(_def.schema._def, refs) : parseAnyDef(refs);
}

// node_modules/zod-to-json-schema/dist/esm/parsers/enum.js
function parseEnumDef(def) {
  return {
    type: "string",
    enum: Array.from(def.values)
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/intersection.js
var isJsonSchema7AllOfType = (type) => "type" in type && type.type === "string" ? !1 : "allOf" in type;
function parseIntersectionDef(def, refs) {
  let allOf = [
    parseDef(def.left._def, {
      ...refs,
      currentPath: [...refs.currentPath, "allOf", "0"]
    }),
    parseDef(def.right._def, {
      ...refs,
      currentPath: [...refs.currentPath, "allOf", "1"]
    })
  ].filter((x) => !!x), unevaluatedProperties = refs.target === "jsonSchema2019-09" ? { unevaluatedProperties: !1 } : void 0, mergedAllOf = [];
  return allOf.forEach((schema) => {
    if (isJsonSchema7AllOfType(schema))
      mergedAllOf.push(...schema.allOf), schema.unevaluatedProperties === void 0 && (unevaluatedProperties = void 0);
    else {
      let nestedSchema = schema;
      if ("additionalProperties" in schema && schema.additionalProperties === !1) {
        let { additionalProperties, ...rest } = schema;
        nestedSchema = rest;
      } else
        unevaluatedProperties = void 0;
      mergedAllOf.push(nestedSchema);
    }
  }), mergedAllOf.length ? {
    allOf: mergedAllOf,
    ...unevaluatedProperties
  } : void 0;
}

// node_modules/zod-to-json-schema/dist/esm/parsers/literal.js
function parseLiteralDef(def, refs) {
  let parsedType2 = typeof def.value;
  return parsedType2 !== "bigint" && parsedType2 !== "number" && parsedType2 !== "boolean" && parsedType2 !== "string" ? {
    type: Array.isArray(def.value) ? "array" : "object"
  } : refs.target === "openApi3" ? {
    type: parsedType2 === "bigint" ? "integer" : parsedType2,
    enum: [def.value]
  } : {
    type: parsedType2 === "bigint" ? "integer" : parsedType2,
    const: def.value
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/string.js
var emojiRegex2, zodPatterns = {
  /**
   * `c` was changed to `[cC]` to replicate /i flag
   */
  cuid: /^[cC][^\s-]{8,}$/,
  cuid2: /^[0-9a-z]+$/,
  ulid: /^[0-9A-HJKMNP-TV-Z]{26}$/,
  /**
   * `a-z` was added to replicate /i flag
   */
  email: /^(?!\.)(?!.*\.\.)([a-zA-Z0-9_'+\-\.]*)[a-zA-Z0-9_+-]@([a-zA-Z0-9][a-zA-Z0-9\-]*\.)+[a-zA-Z]{2,}$/,
  /**
   * Constructed a valid Unicode RegExp
   *
   * Lazily instantiate since this type of regex isn't supported
   * in all envs (e.g. React Native).
   *
   * See:
   * https://github.com/colinhacks/zod/issues/2433
   * Fix in Zod:
   * https://github.com/colinhacks/zod/commit/9340fd51e48576a75adc919bff65dbc4a5d4c99b
   */
  emoji: () => (emojiRegex2 === void 0 && (emojiRegex2 = RegExp("^(\\p{Extended_Pictographic}|\\p{Emoji_Component})+$", "u")), emojiRegex2),
  /**
   * Unused
   */
  uuid: /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/,
  /**
   * Unused
   */
  ipv4: /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])$/,
  ipv4Cidr: /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])\/(3[0-2]|[12]?[0-9])$/,
  /**
   * Unused
   */
  ipv6: /^(([a-f0-9]{1,4}:){7}|::([a-f0-9]{1,4}:){0,6}|([a-f0-9]{1,4}:){1}:([a-f0-9]{1,4}:){0,5}|([a-f0-9]{1,4}:){2}:([a-f0-9]{1,4}:){0,4}|([a-f0-9]{1,4}:){3}:([a-f0-9]{1,4}:){0,3}|([a-f0-9]{1,4}:){4}:([a-f0-9]{1,4}:){0,2}|([a-f0-9]{1,4}:){5}:([a-f0-9]{1,4}:){0,1})([a-f0-9]{1,4}|(((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2}))\.){3}((25[0-5])|(2[0-4][0-9])|(1[0-9]{2})|([0-9]{1,2})))$/,
  ipv6Cidr: /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))\/(12[0-8]|1[01][0-9]|[1-9]?[0-9])$/,
  base64: /^([0-9a-zA-Z+/]{4})*(([0-9a-zA-Z+/]{2}==)|([0-9a-zA-Z+/]{3}=))?$/,
  base64url: /^([0-9a-zA-Z-_]{4})*(([0-9a-zA-Z-_]{2}(==)?)|([0-9a-zA-Z-_]{3}(=)?))?$/,
  nanoid: /^[a-zA-Z0-9_-]{21}$/,
  jwt: /^[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]*$/
};
function parseStringDef(def, refs) {
  let res = {
    type: "string"
  };
  if (def.checks)
    for (let check2 of def.checks)
      switch (check2.kind) {
        case "min":
          setResponseValueAndErrors(res, "minLength", typeof res.minLength == "number" ? Math.max(res.minLength, check2.value) : check2.value, check2.message, refs);
          break;
        case "max":
          setResponseValueAndErrors(res, "maxLength", typeof res.maxLength == "number" ? Math.min(res.maxLength, check2.value) : check2.value, check2.message, refs);
          break;
        case "email":
          switch (refs.emailStrategy) {
            case "format:email":
              addFormat(res, "email", check2.message, refs);
              break;
            case "format:idn-email":
              addFormat(res, "idn-email", check2.message, refs);
              break;
            case "pattern:zod":
              addPattern(res, zodPatterns.email, check2.message, refs);
              break;
          }
          break;
        case "url":
          addFormat(res, "uri", check2.message, refs);
          break;
        case "uuid":
          addFormat(res, "uuid", check2.message, refs);
          break;
        case "regex":
          addPattern(res, check2.regex, check2.message, refs);
          break;
        case "cuid":
          addPattern(res, zodPatterns.cuid, check2.message, refs);
          break;
        case "cuid2":
          addPattern(res, zodPatterns.cuid2, check2.message, refs);
          break;
        case "startsWith":
          addPattern(res, RegExp(`^${escapeLiteralCheckValue(check2.value, refs)}`), check2.message, refs);
          break;
        case "endsWith":
          addPattern(res, RegExp(`${escapeLiteralCheckValue(check2.value, refs)}$`), check2.message, refs);
          break;
        case "datetime":
          addFormat(res, "date-time", check2.message, refs);
          break;
        case "date":
          addFormat(res, "date", check2.message, refs);
          break;
        case "time":
          addFormat(res, "time", check2.message, refs);
          break;
        case "duration":
          addFormat(res, "duration", check2.message, refs);
          break;
        case "length":
          setResponseValueAndErrors(res, "minLength", typeof res.minLength == "number" ? Math.max(res.minLength, check2.value) : check2.value, check2.message, refs), setResponseValueAndErrors(res, "maxLength", typeof res.maxLength == "number" ? Math.min(res.maxLength, check2.value) : check2.value, check2.message, refs);
          break;
        case "includes": {
          addPattern(res, RegExp(escapeLiteralCheckValue(check2.value, refs)), check2.message, refs);
          break;
        }
        case "ip": {
          check2.version !== "v6" && addFormat(res, "ipv4", check2.message, refs), check2.version !== "v4" && addFormat(res, "ipv6", check2.message, refs);
          break;
        }
        case "base64url":
          addPattern(res, zodPatterns.base64url, check2.message, refs);
          break;
        case "jwt":
          addPattern(res, zodPatterns.jwt, check2.message, refs);
          break;
        case "cidr": {
          check2.version !== "v6" && addPattern(res, zodPatterns.ipv4Cidr, check2.message, refs), check2.version !== "v4" && addPattern(res, zodPatterns.ipv6Cidr, check2.message, refs);
          break;
        }
        case "emoji":
          addPattern(res, zodPatterns.emoji(), check2.message, refs);
          break;
        case "ulid": {
          addPattern(res, zodPatterns.ulid, check2.message, refs);
          break;
        }
        case "base64": {
          switch (refs.base64Strategy) {
            case "format:binary": {
              addFormat(res, "binary", check2.message, refs);
              break;
            }
            case "contentEncoding:base64": {
              setResponseValueAndErrors(res, "contentEncoding", "base64", check2.message, refs);
              break;
            }
            case "pattern:zod": {
              addPattern(res, zodPatterns.base64, check2.message, refs);
              break;
            }
          }
          break;
        }
        case "nanoid":
          addPattern(res, zodPatterns.nanoid, check2.message, refs);
        case "toLowerCase":
        case "toUpperCase":
        case "trim":
          break;
        default:
      }
  return res;
}
function escapeLiteralCheckValue(literal2, refs) {
  return refs.patternStrategy === "escape" ? escapeNonAlphaNumeric(literal2) : literal2;
}
var ALPHA_NUMERIC = new Set("ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvxyz0123456789");
function escapeNonAlphaNumeric(source) {
  let result = "";
  for (let i = 0; i < source.length; i++)
    ALPHA_NUMERIC.has(source[i]) || (result += "\\"), result += source[i];
  return result;
}
function addFormat(schema, value, message, refs) {
  schema.format || schema.anyOf?.some((x) => x.format) ? (schema.anyOf || (schema.anyOf = []), schema.format && (schema.anyOf.push({
    format: schema.format,
    ...schema.errorMessage && refs.errorMessages && {
      errorMessage: { format: schema.errorMessage.format }
    }
  }), delete schema.format, schema.errorMessage && (delete schema.errorMessage.format, Object.keys(schema.errorMessage).length === 0 && delete schema.errorMessage)), schema.anyOf.push({
    format: value,
    ...message && refs.errorMessages && { errorMessage: { format: message } }
  })) : setResponseValueAndErrors(schema, "format", value, message, refs);
}
function addPattern(schema, regex, message, refs) {
  schema.pattern || schema.allOf?.some((x) => x.pattern) ? (schema.allOf || (schema.allOf = []), schema.pattern && (schema.allOf.push({
    pattern: schema.pattern,
    ...schema.errorMessage && refs.errorMessages && {
      errorMessage: { pattern: schema.errorMessage.pattern }
    }
  }), delete schema.pattern, schema.errorMessage && (delete schema.errorMessage.pattern, Object.keys(schema.errorMessage).length === 0 && delete schema.errorMessage)), schema.allOf.push({
    pattern: stringifyRegExpWithFlags(regex, refs),
    ...message && refs.errorMessages && { errorMessage: { pattern: message } }
  })) : setResponseValueAndErrors(schema, "pattern", stringifyRegExpWithFlags(regex, refs), message, refs);
}
function stringifyRegExpWithFlags(regex, refs) {
  if (!refs.applyRegexFlags || !regex.flags)
    return regex.source;
  let flags = {
    i: regex.flags.includes("i"),
    m: regex.flags.includes("m"),
    s: regex.flags.includes("s")
    // `.` matches newlines
  }, source = flags.i ? regex.source.toLowerCase() : regex.source, pattern = "", isEscaped = !1, inCharGroup = !1, inCharRange = !1;
  for (let i = 0; i < source.length; i++) {
    if (isEscaped) {
      pattern += source[i], isEscaped = !1;
      continue;
    }
    if (flags.i) {
      if (inCharGroup) {
        if (source[i].match(/[a-z]/)) {
          inCharRange ? (pattern += source[i], pattern += `${source[i - 2]}-${source[i]}`.toUpperCase(), inCharRange = !1) : source[i + 1] === "-" && source[i + 2]?.match(/[a-z]/) ? (pattern += source[i], inCharRange = !0) : pattern += `${source[i]}${source[i].toUpperCase()}`;
          continue;
        }
      } else if (source[i].match(/[a-z]/)) {
        pattern += `[${source[i]}${source[i].toUpperCase()}]`;
        continue;
      }
    }
    if (flags.m) {
      if (source[i] === "^") {
        pattern += `(^|(?<=[\r
]))`;
        continue;
      } else if (source[i] === "$") {
        pattern += `($|(?=[\r
]))`;
        continue;
      }
    }
    if (flags.s && source[i] === ".") {
      pattern += inCharGroup ? `${source[i]}\r
` : `[${source[i]}\r
]`;
      continue;
    }
    pattern += source[i], source[i] === "\\" ? isEscaped = !0 : inCharGroup && source[i] === "]" ? inCharGroup = !1 : !inCharGroup && source[i] === "[" && (inCharGroup = !0);
  }
  try {
    new RegExp(pattern);
  } catch {
    return console.warn(`Could not convert regex pattern at ${refs.currentPath.join("/")} to a flag-independent form! Falling back to the flag-ignorant source`), regex.source;
  }
  return pattern;
}

// node_modules/zod-to-json-schema/dist/esm/parsers/record.js
function parseRecordDef(def, refs) {
  if (refs.target === "openAi" && console.warn("Warning: OpenAI may not support records in schemas! Try an array of key-value pairs instead."), refs.target === "openApi3" && def.keyType?._def.typeName === ZodFirstPartyTypeKind.ZodEnum)
    return {
      type: "object",
      required: def.keyType._def.values,
      properties: def.keyType._def.values.reduce((acc, key) => ({
        ...acc,
        [key]: parseDef(def.valueType._def, {
          ...refs,
          currentPath: [...refs.currentPath, "properties", key]
        }) ?? parseAnyDef(refs)
      }), {}),
      additionalProperties: refs.rejectedAdditionalProperties
    };
  let schema = {
    type: "object",
    additionalProperties: parseDef(def.valueType._def, {
      ...refs,
      currentPath: [...refs.currentPath, "additionalProperties"]
    }) ?? refs.allowedAdditionalProperties
  };
  if (refs.target === "openApi3")
    return schema;
  if (def.keyType?._def.typeName === ZodFirstPartyTypeKind.ZodString && def.keyType._def.checks?.length) {
    let { type, ...keyType } = parseStringDef(def.keyType._def, refs);
    return {
      ...schema,
      propertyNames: keyType
    };
  } else {
    if (def.keyType?._def.typeName === ZodFirstPartyTypeKind.ZodEnum)
      return {
        ...schema,
        propertyNames: {
          enum: def.keyType._def.values
        }
      };
    if (def.keyType?._def.typeName === ZodFirstPartyTypeKind.ZodBranded && def.keyType._def.type._def.typeName === ZodFirstPartyTypeKind.ZodString && def.keyType._def.type._def.checks?.length) {
      let { type, ...keyType } = parseBrandedDef(def.keyType._def, refs);
      return {
        ...schema,
        propertyNames: keyType
      };
    }
  }
  return schema;
}

// node_modules/zod-to-json-schema/dist/esm/parsers/map.js
function parseMapDef(def, refs) {
  if (refs.mapStrategy === "record")
    return parseRecordDef(def, refs);
  let keys = parseDef(def.keyType._def, {
    ...refs,
    currentPath: [...refs.currentPath, "items", "items", "0"]
  }) || parseAnyDef(refs), values = parseDef(def.valueType._def, {
    ...refs,
    currentPath: [...refs.currentPath, "items", "items", "1"]
  }) || parseAnyDef(refs);
  return {
    type: "array",
    maxItems: 125,
    items: {
      type: "array",
      items: [keys, values],
      minItems: 2,
      maxItems: 2
    }
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/nativeEnum.js
function parseNativeEnumDef(def) {
  let object3 = def.values, actualValues = Object.keys(def.values).filter((key) => typeof object3[object3[key]] != "number").map((key) => object3[key]), parsedTypes = Array.from(new Set(actualValues.map((values) => typeof values)));
  return {
    type: parsedTypes.length === 1 ? parsedTypes[0] === "string" ? "string" : "number" : ["string", "number"],
    enum: actualValues
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/never.js
function parseNeverDef(refs) {
  return refs.target === "openAi" ? void 0 : {
    not: parseAnyDef({
      ...refs,
      currentPath: [...refs.currentPath, "not"]
    })
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/null.js
function parseNullDef(refs) {
  return refs.target === "openApi3" ? {
    enum: ["null"],
    nullable: !0
  } : {
    type: "null"
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/union.js
var primitiveMappings = {
  ZodString: "string",
  ZodNumber: "number",
  ZodBigInt: "integer",
  ZodBoolean: "boolean",
  ZodNull: "null"
};
function parseUnionDef(def, refs) {
  if (refs.target === "openApi3")
    return asAnyOf(def, refs);
  let options = def.options instanceof Map ? Array.from(def.options.values()) : def.options;
  if (options.every((x) => x._def.typeName in primitiveMappings && (!x._def.checks || !x._def.checks.length))) {
    let types = options.reduce((types2, x) => {
      let type = primitiveMappings[x._def.typeName];
      return type && !types2.includes(type) ? [...types2, type] : types2;
    }, []);
    return {
      type: types.length > 1 ? types : types[0]
    };
  } else if (options.every((x) => x._def.typeName === "ZodLiteral" && !x.description)) {
    let types = options.reduce((acc, x) => {
      let type = typeof x._def.value;
      switch (type) {
        case "string":
        case "number":
        case "boolean":
          return [...acc, type];
        case "bigint":
          return [...acc, "integer"];
        case "object":
          if (x._def.value === null)
            return [...acc, "null"];
        case "symbol":
        case "undefined":
        case "function":
        default:
          return acc;
      }
    }, []);
    if (types.length === options.length) {
      let uniqueTypes = types.filter((x, i, a) => a.indexOf(x) === i);
      return {
        type: uniqueTypes.length > 1 ? uniqueTypes : uniqueTypes[0],
        enum: options.reduce((acc, x) => acc.includes(x._def.value) ? acc : [...acc, x._def.value], [])
      };
    }
  } else if (options.every((x) => x._def.typeName === "ZodEnum"))
    return {
      type: "string",
      enum: options.reduce((acc, x) => [
        ...acc,
        ...x._def.values.filter((x2) => !acc.includes(x2))
      ], [])
    };
  return asAnyOf(def, refs);
}
var asAnyOf = (def, refs) => {
  let anyOf = (def.options instanceof Map ? Array.from(def.options.values()) : def.options).map((x, i) => parseDef(x._def, {
    ...refs,
    currentPath: [...refs.currentPath, "anyOf", `${i}`]
  })).filter((x) => !!x && (!refs.strictUnions || typeof x == "object" && Object.keys(x).length > 0));
  return anyOf.length ? { anyOf } : void 0;
};

// node_modules/zod-to-json-schema/dist/esm/parsers/nullable.js
function parseNullableDef(def, refs) {
  if (["ZodString", "ZodNumber", "ZodBigInt", "ZodBoolean", "ZodNull"].includes(def.innerType._def.typeName) && (!def.innerType._def.checks || !def.innerType._def.checks.length))
    return refs.target === "openApi3" ? {
      type: primitiveMappings[def.innerType._def.typeName],
      nullable: !0
    } : {
      type: [
        primitiveMappings[def.innerType._def.typeName],
        "null"
      ]
    };
  if (refs.target === "openApi3") {
    let base2 = parseDef(def.innerType._def, {
      ...refs,
      currentPath: [...refs.currentPath]
    });
    return base2 && "$ref" in base2 ? { allOf: [base2], nullable: !0 } : base2 && { ...base2, nullable: !0 };
  }
  let base = parseDef(def.innerType._def, {
    ...refs,
    currentPath: [...refs.currentPath, "anyOf", "0"]
  });
  return base && { anyOf: [base, { type: "null" }] };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/number.js
function parseNumberDef(def, refs) {
  let res = {
    type: "number"
  };
  if (!def.checks)
    return res;
  for (let check2 of def.checks)
    switch (check2.kind) {
      case "int":
        res.type = "integer", addErrorMessage(res, "type", check2.message, refs);
        break;
      case "min":
        refs.target === "jsonSchema7" ? check2.inclusive ? setResponseValueAndErrors(res, "minimum", check2.value, check2.message, refs) : setResponseValueAndErrors(res, "exclusiveMinimum", check2.value, check2.message, refs) : (check2.inclusive || (res.exclusiveMinimum = !0), setResponseValueAndErrors(res, "minimum", check2.value, check2.message, refs));
        break;
      case "max":
        refs.target === "jsonSchema7" ? check2.inclusive ? setResponseValueAndErrors(res, "maximum", check2.value, check2.message, refs) : setResponseValueAndErrors(res, "exclusiveMaximum", check2.value, check2.message, refs) : (check2.inclusive || (res.exclusiveMaximum = !0), setResponseValueAndErrors(res, "maximum", check2.value, check2.message, refs));
        break;
      case "multipleOf":
        setResponseValueAndErrors(res, "multipleOf", check2.value, check2.message, refs);
        break;
    }
  return res;
}

// node_modules/zod-to-json-schema/dist/esm/parsers/object.js
function parseObjectDef(def, refs) {
  let forceOptionalIntoNullable = refs.target === "openAi", result = {
    type: "object",
    properties: {}
  }, required2 = [], shape = def.shape();
  for (let propName in shape) {
    let propDef = shape[propName];
    if (propDef === void 0 || propDef._def === void 0)
      continue;
    let propOptional = safeIsOptional(propDef);
    propOptional && forceOptionalIntoNullable && (propDef._def.typeName === "ZodOptional" && (propDef = propDef._def.innerType), propDef.isNullable() || (propDef = propDef.nullable()), propOptional = !1);
    let parsedDef = parseDef(propDef._def, {
      ...refs,
      currentPath: [...refs.currentPath, "properties", propName],
      propertyPath: [...refs.currentPath, "properties", propName]
    });
    parsedDef !== void 0 && (result.properties[propName] = parsedDef, propOptional || required2.push(propName));
  }
  required2.length && (result.required = required2);
  let additionalProperties = decideAdditionalProperties(def, refs);
  return additionalProperties !== void 0 && (result.additionalProperties = additionalProperties), result;
}
function decideAdditionalProperties(def, refs) {
  if (def.catchall._def.typeName !== "ZodNever")
    return parseDef(def.catchall._def, {
      ...refs,
      currentPath: [...refs.currentPath, "additionalProperties"]
    });
  switch (def.unknownKeys) {
    case "passthrough":
      return refs.allowedAdditionalProperties;
    case "strict":
      return refs.rejectedAdditionalProperties;
    case "strip":
      return refs.removeAdditionalStrategy === "strict" ? refs.allowedAdditionalProperties : refs.rejectedAdditionalProperties;
  }
}
function safeIsOptional(schema) {
  try {
    return schema.isOptional();
  } catch {
    return !0;
  }
}

// node_modules/zod-to-json-schema/dist/esm/parsers/optional.js
var parseOptionalDef = (def, refs) => {
  if (refs.currentPath.toString() === refs.propertyPath?.toString())
    return parseDef(def.innerType._def, refs);
  let innerSchema = parseDef(def.innerType._def, {
    ...refs,
    currentPath: [...refs.currentPath, "anyOf", "1"]
  });
  return innerSchema ? {
    anyOf: [
      {
        not: parseAnyDef(refs)
      },
      innerSchema
    ]
  } : parseAnyDef(refs);
};

// node_modules/zod-to-json-schema/dist/esm/parsers/pipeline.js
var parsePipelineDef = (def, refs) => {
  if (refs.pipeStrategy === "input")
    return parseDef(def.in._def, refs);
  if (refs.pipeStrategy === "output")
    return parseDef(def.out._def, refs);
  let a = parseDef(def.in._def, {
    ...refs,
    currentPath: [...refs.currentPath, "allOf", "0"]
  }), b = parseDef(def.out._def, {
    ...refs,
    currentPath: [...refs.currentPath, "allOf", a ? "1" : "0"]
  });
  return {
    allOf: [a, b].filter((x) => x !== void 0)
  };
};

// node_modules/zod-to-json-schema/dist/esm/parsers/promise.js
function parsePromiseDef(def, refs) {
  return parseDef(def.type._def, refs);
}

// node_modules/zod-to-json-schema/dist/esm/parsers/set.js
function parseSetDef(def, refs) {
  let schema = {
    type: "array",
    uniqueItems: !0,
    items: parseDef(def.valueType._def, {
      ...refs,
      currentPath: [...refs.currentPath, "items"]
    })
  };
  return def.minSize && setResponseValueAndErrors(schema, "minItems", def.minSize.value, def.minSize.message, refs), def.maxSize && setResponseValueAndErrors(schema, "maxItems", def.maxSize.value, def.maxSize.message, refs), schema;
}

// node_modules/zod-to-json-schema/dist/esm/parsers/tuple.js
function parseTupleDef(def, refs) {
  return def.rest ? {
    type: "array",
    minItems: def.items.length,
    items: def.items.map((x, i) => parseDef(x._def, {
      ...refs,
      currentPath: [...refs.currentPath, "items", `${i}`]
    })).reduce((acc, x) => x === void 0 ? acc : [...acc, x], []),
    additionalItems: parseDef(def.rest._def, {
      ...refs,
      currentPath: [...refs.currentPath, "additionalItems"]
    })
  } : {
    type: "array",
    minItems: def.items.length,
    maxItems: def.items.length,
    items: def.items.map((x, i) => parseDef(x._def, {
      ...refs,
      currentPath: [...refs.currentPath, "items", `${i}`]
    })).reduce((acc, x) => x === void 0 ? acc : [...acc, x], [])
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/undefined.js
function parseUndefinedDef(refs) {
  return {
    not: parseAnyDef(refs)
  };
}

// node_modules/zod-to-json-schema/dist/esm/parsers/unknown.js
function parseUnknownDef(refs) {
  return parseAnyDef(refs);
}

// node_modules/zod-to-json-schema/dist/esm/parsers/readonly.js
var parseReadonlyDef = (def, refs) => parseDef(def.innerType._def, refs);

// node_modules/zod-to-json-schema/dist/esm/selectParser.js
var selectParser = (def, typeName, refs) => {
  switch (typeName) {
    case ZodFirstPartyTypeKind.ZodString:
      return parseStringDef(def, refs);
    case ZodFirstPartyTypeKind.ZodNumber:
      return parseNumberDef(def, refs);
    case ZodFirstPartyTypeKind.ZodObject:
      return parseObjectDef(def, refs);
    case ZodFirstPartyTypeKind.ZodBigInt:
      return parseBigintDef(def, refs);
    case ZodFirstPartyTypeKind.ZodBoolean:
      return parseBooleanDef();
    case ZodFirstPartyTypeKind.ZodDate:
      return parseDateDef(def, refs);
    case ZodFirstPartyTypeKind.ZodUndefined:
      return parseUndefinedDef(refs);
    case ZodFirstPartyTypeKind.ZodNull:
      return parseNullDef(refs);
    case ZodFirstPartyTypeKind.ZodArray:
      return parseArrayDef(def, refs);
    case ZodFirstPartyTypeKind.ZodUnion:
    case ZodFirstPartyTypeKind.ZodDiscriminatedUnion:
      return parseUnionDef(def, refs);
    case ZodFirstPartyTypeKind.ZodIntersection:
      return parseIntersectionDef(def, refs);
    case ZodFirstPartyTypeKind.ZodTuple:
      return parseTupleDef(def, refs);
    case ZodFirstPartyTypeKind.ZodRecord:
      return parseRecordDef(def, refs);
    case ZodFirstPartyTypeKind.ZodLiteral:
      return parseLiteralDef(def, refs);
    case ZodFirstPartyTypeKind.ZodEnum:
      return parseEnumDef(def);
    case ZodFirstPartyTypeKind.ZodNativeEnum:
      return parseNativeEnumDef(def);
    case ZodFirstPartyTypeKind.ZodNullable:
      return parseNullableDef(def, refs);
    case ZodFirstPartyTypeKind.ZodOptional:
      return parseOptionalDef(def, refs);
    case ZodFirstPartyTypeKind.ZodMap:
      return parseMapDef(def, refs);
    case ZodFirstPartyTypeKind.ZodSet:
      return parseSetDef(def, refs);
    case ZodFirstPartyTypeKind.ZodLazy:
      return () => def.getter()._def;
    case ZodFirstPartyTypeKind.ZodPromise:
      return parsePromiseDef(def, refs);
    case ZodFirstPartyTypeKind.ZodNaN:
    case ZodFirstPartyTypeKind.ZodNever:
      return parseNeverDef(refs);
    case ZodFirstPartyTypeKind.ZodEffects:
      return parseEffectsDef(def, refs);
    case ZodFirstPartyTypeKind.ZodAny:
      return parseAnyDef(refs);
    case ZodFirstPartyTypeKind.ZodUnknown:
      return parseUnknownDef(refs);
    case ZodFirstPartyTypeKind.ZodDefault:
      return parseDefaultDef(def, refs);
    case ZodFirstPartyTypeKind.ZodBranded:
      return parseBrandedDef(def, refs);
    case ZodFirstPartyTypeKind.ZodReadonly:
      return parseReadonlyDef(def, refs);
    case ZodFirstPartyTypeKind.ZodCatch:
      return parseCatchDef(def, refs);
    case ZodFirstPartyTypeKind.ZodPipeline:
      return parsePipelineDef(def, refs);
    case ZodFirstPartyTypeKind.ZodFunction:
    case ZodFirstPartyTypeKind.ZodVoid:
    case ZodFirstPartyTypeKind.ZodSymbol:
      return;
    default:
      return /* @__PURE__ */ ((_) => {
      })(typeName);
  }
};

// node_modules/zod-to-json-schema/dist/esm/parseDef.js
function parseDef(def, refs, forceResolution = !1) {
  let seenItem = refs.seen.get(def);
  if (refs.override) {
    let overrideResult = refs.override?.(def, refs, seenItem, forceResolution);
    if (overrideResult !== ignoreOverride)
      return overrideResult;
  }
  if (seenItem && !forceResolution) {
    let seenSchema = get$ref(seenItem, refs);
    if (seenSchema !== void 0)
      return seenSchema;
  }
  let newItem = { def, path: refs.currentPath, jsonSchema: void 0 };
  refs.seen.set(def, newItem);
  let jsonSchemaOrGetter = selectParser(def, def.typeName, refs), jsonSchema = typeof jsonSchemaOrGetter == "function" ? parseDef(jsonSchemaOrGetter(), refs) : jsonSchemaOrGetter;
  if (jsonSchema && addMeta(def, refs, jsonSchema), refs.postProcess) {
    let postProcessResult = refs.postProcess(jsonSchema, def, refs);
    return newItem.jsonSchema = jsonSchema, postProcessResult;
  }
  return newItem.jsonSchema = jsonSchema, jsonSchema;
}
var get$ref = (item, refs) => {
  switch (refs.$refStrategy) {
    case "root":
      return { $ref: item.path.join("/") };
    case "relative":
      return { $ref: getRelativePath(refs.currentPath, item.path) };
    case "none":
    case "seen":
      return item.path.length < refs.currentPath.length && item.path.every((value, index) => refs.currentPath[index] === value) ? (console.warn(`Recursive reference detected at ${refs.currentPath.join("/")}! Defaulting to any`), parseAnyDef(refs)) : refs.$refStrategy === "seen" ? parseAnyDef(refs) : void 0;
  }
}, addMeta = (def, refs, jsonSchema) => (def.description && (jsonSchema.description = def.description, refs.markdownDescription && (jsonSchema.markdownDescription = def.description)), jsonSchema);

// node_modules/zod-to-json-schema/dist/esm/zodToJsonSchema.js
var zodToJsonSchema = (schema, options) => {
  let refs = getRefs(options), definitions = typeof options == "object" && options.definitions ? Object.entries(options.definitions).reduce((acc, [name2, schema2]) => ({
    ...acc,
    [name2]: parseDef(schema2._def, {
      ...refs,
      currentPath: [...refs.basePath, refs.definitionPath, name2]
    }, !0) ?? parseAnyDef(refs)
  }), {}) : void 0, name = typeof options == "string" ? options : options?.nameStrategy === "title" ? void 0 : options?.name, main = parseDef(schema._def, name === void 0 ? refs : {
    ...refs,
    currentPath: [...refs.basePath, refs.definitionPath, name]
  }, !1) ?? parseAnyDef(refs), title = typeof options == "object" && options.name !== void 0 && options.nameStrategy === "title" ? options.name : void 0;
  title !== void 0 && (main.title = title), refs.flags.hasReferencedOpenAiAnyType && (definitions || (definitions = {}), definitions[refs.openAiAnyTypeName] || (definitions[refs.openAiAnyTypeName] = {
    // Skipping "object" as no properties can be defined and additionalProperties must be "false"
    type: ["string", "number", "integer", "boolean", "array", "null"],
    items: {
      $ref: refs.$refStrategy === "relative" ? "1" : [
        ...refs.basePath,
        refs.definitionPath,
        refs.openAiAnyTypeName
      ].join("/")
    }
  }));
  let combined = name === void 0 ? definitions ? {
    ...main,
    [refs.definitionPath]: definitions
  } : main : {
    $ref: [
      ...refs.$refStrategy === "relative" ? [] : refs.basePath,
      refs.definitionPath,
      name
    ].join("/"),
    [refs.definitionPath]: {
      ...definitions,
      [name]: main
    }
  };
  return refs.target === "jsonSchema7" ? combined.$schema = "http://json-schema.org/draft-07/schema#" : (refs.target === "jsonSchema2019-09" || refs.target === "openAi") && (combined.$schema = "https://json-schema.org/draft/2019-09/schema#"), refs.target === "openAi" && ("anyOf" in combined || "oneOf" in combined || "allOf" in combined || "type" in combined && Array.isArray(combined.type)) && console.warn("Warning: OpenAI may not support schemas with unions as roots! Try wrapping it in an object property."), combined;
};

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/zod-json-schema-compat.js
function mapMiniTarget(t) {
  return !t || t === "jsonSchema7" || t === "draft-7" ? "draft-7" : t === "jsonSchema2019-09" || t === "draft-2020-12" ? "draft-2020-12" : "draft-7";
}
function toJsonSchemaCompat(schema, opts) {
  return isZ4Schema(schema) ? toJSONSchema(schema, {
    target: mapMiniTarget(opts?.target),
    io: opts?.pipeStrategy ?? "input"
  }) : zodToJsonSchema(schema, {
    strictUnions: opts?.strictUnions ?? !0,
    pipeStrategy: opts?.pipeStrategy ?? "input"
  });
}
function getMethodLiteral(schema) {
  let methodSchema = getObjectShape(schema)?.method;
  if (!methodSchema)
    throw new Error("Schema is missing a method literal");
  let value = getLiteralValue(methodSchema);
  if (typeof value != "string")
    throw new Error("Schema method literal must be a string");
  return value;
}
function parseWithCompat(schema, data) {
  let result = safeParse2(schema, data);
  if (!result.success)
    throw result.error;
  return result.data;
}

// node_modules/@modelcontextprotocol/sdk/dist/esm/shared/protocol.js
var DEFAULT_REQUEST_TIMEOUT_MSEC = 6e4, Protocol = class {
  constructor(_options) {
    this._options = _options, this._requestMessageId = 0, this._requestHandlers = /* @__PURE__ */ new Map(), this._requestHandlerAbortControllers = /* @__PURE__ */ new Map(), this._notificationHandlers = /* @__PURE__ */ new Map(), this._responseHandlers = /* @__PURE__ */ new Map(), this._progressHandlers = /* @__PURE__ */ new Map(), this._timeoutInfo = /* @__PURE__ */ new Map(), this._pendingDebouncedNotifications = /* @__PURE__ */ new Set(), this._taskProgressTokens = /* @__PURE__ */ new Map(), this._requestResolvers = /* @__PURE__ */ new Map(), this.setNotificationHandler(CancelledNotificationSchema, (notification) => {
      this._oncancel(notification);
    }), this.setNotificationHandler(ProgressNotificationSchema, (notification) => {
      this._onprogress(notification);
    }), this.setRequestHandler(
      PingRequestSchema,
      // Automatic pong by default.
      (_request) => ({})
    ), this._taskStore = _options?.taskStore, this._taskMessageQueue = _options?.taskMessageQueue, this._taskStore && (this.setRequestHandler(GetTaskRequestSchema, async (request, extra) => {
      let task = await this._taskStore.getTask(request.params.taskId, extra.sessionId);
      if (!task)
        throw new McpError(ErrorCode.InvalidParams, "Failed to retrieve task: Task not found");
      return {
        ...task
      };
    }), this.setRequestHandler(GetTaskPayloadRequestSchema, async (request, extra) => {
      let handleTaskResult = async () => {
        let taskId = request.params.taskId;
        if (!await this._taskStore.getTask(taskId, extra.sessionId))
          throw new McpError(ErrorCode.InvalidParams, `Task not found: ${taskId}`);
        if (this._taskMessageQueue) {
          let queuedMessage;
          for (; queuedMessage = await this._taskMessageQueue.dequeue(taskId, extra.sessionId); ) {
            if (queuedMessage.type === "response" || queuedMessage.type === "error") {
              let message = queuedMessage.message, requestId = message.id, resolver = this._requestResolvers.get(requestId);
              if (resolver)
                if (this._requestResolvers.delete(requestId), queuedMessage.type === "response")
                  resolver(message);
                else {
                  let errorMessage = message, error2 = new McpError(errorMessage.error.code, errorMessage.error.message, errorMessage.error.data);
                  resolver(error2);
                }
              else {
                let messageType = queuedMessage.type === "response" ? "Response" : "Error";
                this._onerror(new Error(`${messageType} handler missing for request ${requestId}`));
              }
              continue;
            }
            await this._transport?.send(queuedMessage.message, { relatedRequestId: extra.requestId });
          }
        }
        let task = await this._taskStore.getTask(taskId, extra.sessionId);
        if (!task)
          throw new McpError(ErrorCode.InvalidParams, `Task not found: ${taskId}`);
        if (!isTerminal(task.status))
          return await this._waitForTaskUpdate(taskId, extra.signal, extra.sessionId), await handleTaskResult();
        if (isTerminal(task.status)) {
          let result = await this._taskStore.getTaskResult(taskId, extra.sessionId);
          return this._clearTaskQueue(taskId, extra.sessionId), {
            ...result,
            _meta: {
              ...result._meta,
              [RELATED_TASK_META_KEY]: {
                taskId
              }
            }
          };
        }
        return await handleTaskResult();
      };
      return await handleTaskResult();
    }), this.setRequestHandler(ListTasksRequestSchema, async (request, extra) => {
      try {
        let { tasks, nextCursor } = await this._taskStore.listTasks(request.params?.cursor, extra.sessionId);
        return {
          tasks,
          nextCursor,
          _meta: {}
        };
      } catch (error2) {
        throw new McpError(ErrorCode.InvalidParams, `Failed to list tasks: ${error2 instanceof Error ? error2.message : String(error2)}`);
      }
    }), this.setRequestHandler(CancelTaskRequestSchema, async (request, extra) => {
      try {
        let task = await this._taskStore.getTask(request.params.taskId, extra.sessionId);
        if (!task)
          throw new McpError(ErrorCode.InvalidParams, `Task not found: ${request.params.taskId}`);
        if (isTerminal(task.status))
          throw new McpError(ErrorCode.InvalidParams, `Cannot cancel task in terminal status: ${task.status}`);
        await this._taskStore.updateTaskStatus(request.params.taskId, "cancelled", "Client cancelled task execution.", extra.sessionId), this._clearTaskQueue(request.params.taskId, extra.sessionId);
        let cancelledTask = await this._taskStore.getTask(request.params.taskId, extra.sessionId);
        if (!cancelledTask)
          throw new McpError(ErrorCode.InvalidParams, `Task not found after cancellation: ${request.params.taskId}`);
        return {
          _meta: {},
          ...cancelledTask
        };
      } catch (error2) {
        throw error2 instanceof McpError ? error2 : new McpError(ErrorCode.InvalidRequest, `Failed to cancel task: ${error2 instanceof Error ? error2.message : String(error2)}`);
      }
    }));
  }
  async _oncancel(notification) {
    if (!notification.params.requestId)
      return;
    this._requestHandlerAbortControllers.get(notification.params.requestId)?.abort(notification.params.reason);
  }
  _setupTimeout(messageId, timeout, maxTotalTimeout, onTimeout, resetTimeoutOnProgress = !1) {
    this._timeoutInfo.set(messageId, {
      timeoutId: setTimeout(onTimeout, timeout),
      startTime: Date.now(),
      timeout,
      maxTotalTimeout,
      resetTimeoutOnProgress,
      onTimeout
    });
  }
  _resetTimeout(messageId) {
    let info = this._timeoutInfo.get(messageId);
    if (!info)
      return !1;
    let totalElapsed = Date.now() - info.startTime;
    if (info.maxTotalTimeout && totalElapsed >= info.maxTotalTimeout)
      throw this._timeoutInfo.delete(messageId), McpError.fromError(ErrorCode.RequestTimeout, "Maximum total timeout exceeded", {
        maxTotalTimeout: info.maxTotalTimeout,
        totalElapsed
      });
    return clearTimeout(info.timeoutId), info.timeoutId = setTimeout(info.onTimeout, info.timeout), !0;
  }
  _cleanupTimeout(messageId) {
    let info = this._timeoutInfo.get(messageId);
    info && (clearTimeout(info.timeoutId), this._timeoutInfo.delete(messageId));
  }
  /**
   * Attaches to the given transport, starts it, and starts listening for messages.
   *
   * The Protocol object assumes ownership of the Transport, replacing any callbacks that have already been set, and expects that it is the only user of the Transport instance going forward.
   */
  async connect(transport) {
    if (this._transport)
      throw new Error("Already connected to a transport. Call close() before connecting to a new transport, or use a separate Protocol instance per connection.");
    this._transport = transport;
    let _onclose = this.transport?.onclose;
    this._transport.onclose = () => {
      _onclose?.(), this._onclose();
    };
    let _onerror = this.transport?.onerror;
    this._transport.onerror = (error2) => {
      _onerror?.(error2), this._onerror(error2);
    };
    let _onmessage = this._transport?.onmessage;
    this._transport.onmessage = (message, extra) => {
      _onmessage?.(message, extra), isJSONRPCResultResponse(message) || isJSONRPCErrorResponse(message) ? this._onresponse(message) : isJSONRPCRequest(message) ? this._onrequest(message, extra) : isJSONRPCNotification(message) ? this._onnotification(message) : this._onerror(new Error(`Unknown message type: ${JSON.stringify(message)}`));
    }, await this._transport.start();
  }
  _onclose() {
    let responseHandlers = this._responseHandlers;
    this._responseHandlers = /* @__PURE__ */ new Map(), this._progressHandlers.clear(), this._taskProgressTokens.clear(), this._pendingDebouncedNotifications.clear();
    for (let info of this._timeoutInfo.values())
      clearTimeout(info.timeoutId);
    this._timeoutInfo.clear();
    for (let controller of this._requestHandlerAbortControllers.values())
      controller.abort();
    this._requestHandlerAbortControllers.clear();
    let error2 = McpError.fromError(ErrorCode.ConnectionClosed, "Connection closed");
    this._transport = void 0, this.onclose?.();
    for (let handler of responseHandlers.values())
      handler(error2);
  }
  _onerror(error2) {
    this.onerror?.(error2);
  }
  _onnotification(notification) {
    let handler = this._notificationHandlers.get(notification.method) ?? this.fallbackNotificationHandler;
    handler !== void 0 && Promise.resolve().then(() => handler(notification)).catch((error2) => this._onerror(new Error(`Uncaught error in notification handler: ${error2}`)));
  }
  _onrequest(request, extra) {
    let handler = this._requestHandlers.get(request.method) ?? this.fallbackRequestHandler, capturedTransport = this._transport, relatedTaskId = request.params?._meta?.[RELATED_TASK_META_KEY]?.taskId, sessionId = capturedTransport?.sessionId, store = this._taskStore, relatedTaskFound = !0, relatedTaskLookup;
    if (relatedTaskId && store && this._taskMessageQueue && sessionId !== void 0 && (relatedTaskFound = !1, relatedTaskLookup = (async () => {
      if (!await store.getTask(relatedTaskId, sessionId))
        throw new McpError(ErrorCode.InvalidParams, `Task not found: ${relatedTaskId}`);
      relatedTaskFound = !0;
    })()), handler === void 0) {
      let errorResponse = {
        jsonrpc: "2.0",
        id: request.id,
        error: {
          code: ErrorCode.MethodNotFound,
          message: "Method not found"
        }
      };
      if (relatedTaskId && relatedTaskLookup) {
        let queuedError = { type: "error", message: errorResponse, timestamp: Date.now() };
        relatedTaskLookup.then(() => this._enqueueTaskMessage(relatedTaskId, queuedError, sessionId), () => capturedTransport?.send(errorResponse)).catch((error2) => this._onerror(new Error(`Failed to send an error response: ${error2}`)));
      } else relatedTaskId && this._taskMessageQueue ? this._enqueueTaskMessage(relatedTaskId, {
        type: "error",
        message: errorResponse,
        timestamp: Date.now()
      }, capturedTransport?.sessionId).catch((error2) => this._onerror(new Error(`Failed to enqueue error response: ${error2}`))) : capturedTransport?.send(errorResponse).catch((error2) => this._onerror(new Error(`Failed to send an error response: ${error2}`)));
      return;
    }
    let abortController = new AbortController();
    this._requestHandlerAbortControllers.set(request.id, abortController);
    let taskCreationParams = isTaskAugmentedRequestParams(request.params) ? request.params.task : void 0, taskStore = this._taskStore ? this.requestTaskStore(request, capturedTransport?.sessionId) : void 0, fullExtra = {
      signal: abortController.signal,
      sessionId: capturedTransport?.sessionId,
      _meta: request.params?._meta,
      sendNotification: async (notification) => {
        if (abortController.signal.aborted)
          return;
        let notificationOptions = { relatedRequestId: request.id };
        relatedTaskId && (notificationOptions.relatedTask = { taskId: relatedTaskId }), await this.notification(notification, notificationOptions);
      },
      sendRequest: async (r, resultSchema, options) => {
        if (abortController.signal.aborted)
          throw new McpError(ErrorCode.ConnectionClosed, "Request was cancelled");
        let requestOptions = { ...options, relatedRequestId: request.id };
        relatedTaskId && !requestOptions.relatedTask && (requestOptions.relatedTask = { taskId: relatedTaskId });
        let effectiveTaskId = requestOptions.relatedTask?.taskId ?? relatedTaskId;
        return effectiveTaskId && taskStore && await taskStore.updateTaskStatus(effectiveTaskId, "input_required"), await this.request(r, resultSchema, requestOptions);
      },
      authInfo: extra?.authInfo,
      requestId: request.id,
      requestInfo: extra?.requestInfo,
      taskId: relatedTaskId,
      taskStore,
      taskRequestedTtl: taskCreationParams?.ttl,
      closeSSEStream: extra?.closeSSEStream,
      closeStandaloneSSEStream: extra?.closeStandaloneSSEStream
    };
    (relatedTaskLookup ?? Promise.resolve()).then(() => {
      if (relatedTaskLookup && abortController.signal.aborted)
        throw new McpError(ErrorCode.ConnectionClosed, "Request was cancelled");
      taskCreationParams && this.assertTaskHandlerCapability(request.method);
    }).then(() => handler(request, fullExtra)).then(async (result) => {
      if (abortController.signal.aborted)
        return;
      let response = {
        result,
        jsonrpc: "2.0",
        id: request.id
      };
      relatedTaskId && this._taskMessageQueue ? await this._enqueueTaskMessage(relatedTaskId, {
        type: "response",
        message: response,
        timestamp: Date.now()
      }, capturedTransport?.sessionId) : await capturedTransport?.send(response);
    }, async (error2) => {
      if (abortController.signal.aborted)
        return;
      let errorResponse = {
        jsonrpc: "2.0",
        id: request.id,
        error: {
          code: Number.isSafeInteger(error2.code) ? error2.code : ErrorCode.InternalError,
          message: error2.message ?? "Internal error",
          ...error2.data !== void 0 && { data: error2.data }
        }
      };
      relatedTaskId && this._taskMessageQueue && relatedTaskFound ? await this._enqueueTaskMessage(relatedTaskId, {
        type: "error",
        message: errorResponse,
        timestamp: Date.now()
      }, capturedTransport?.sessionId) : await capturedTransport?.send(errorResponse);
    }).catch((error2) => this._onerror(new Error(`Failed to send response: ${error2}`))).finally(() => {
      this._requestHandlerAbortControllers.get(request.id) === abortController && this._requestHandlerAbortControllers.delete(request.id);
    });
  }
  _onprogress(notification) {
    let { progressToken, ...params } = notification.params, messageId = Number(progressToken), handler = this._progressHandlers.get(messageId);
    if (!handler) {
      this._onerror(new Error(`Received a progress notification for an unknown token: ${JSON.stringify(notification)}`));
      return;
    }
    let responseHandler = this._responseHandlers.get(messageId), timeoutInfo = this._timeoutInfo.get(messageId);
    if (timeoutInfo && responseHandler && timeoutInfo.resetTimeoutOnProgress)
      try {
        this._resetTimeout(messageId);
      } catch (error2) {
        this._responseHandlers.delete(messageId), this._progressHandlers.delete(messageId), this._cleanupTimeout(messageId), responseHandler(error2);
        return;
      }
    handler(params);
  }
  _onresponse(response) {
    let messageId = Number(response.id), resolver = this._requestResolvers.get(messageId);
    if (resolver) {
      if (this._requestResolvers.delete(messageId), isJSONRPCResultResponse(response))
        resolver(response);
      else {
        let error2 = new McpError(response.error.code, response.error.message, response.error.data);
        resolver(error2);
      }
      return;
    }
    let handler = this._responseHandlers.get(messageId);
    if (handler === void 0) {
      this._onerror(new Error(`Received a response for an unknown message ID: ${JSON.stringify(response)}`));
      return;
    }
    this._responseHandlers.delete(messageId), this._cleanupTimeout(messageId);
    let isTaskResponse = !1;
    if (isJSONRPCResultResponse(response) && response.result && typeof response.result == "object") {
      let result = response.result;
      if (result.task && typeof result.task == "object") {
        let task = result.task;
        typeof task.taskId == "string" && (isTaskResponse = !0, this._taskProgressTokens.set(task.taskId, messageId));
      }
    }
    if (isTaskResponse || this._progressHandlers.delete(messageId), isJSONRPCResultResponse(response))
      handler(response);
    else {
      let error2 = McpError.fromError(response.error.code, response.error.message, response.error.data);
      handler(error2);
    }
  }
  get transport() {
    return this._transport;
  }
  /**
   * Closes the connection.
   */
  async close() {
    await this._transport?.close();
  }
  /**
   * Sends a request and returns an AsyncGenerator that yields response messages.
   * The generator is guaranteed to end with either a 'result' or 'error' message.
   *
   * @example
   * ```typescript
   * const stream = protocol.requestStream(request, resultSchema, options);
   * for await (const message of stream) {
   *   switch (message.type) {
   *     case 'taskCreated':
   *       console.log('Task created:', message.task.taskId);
   *       break;
   *     case 'taskStatus':
   *       console.log('Task status:', message.task.status);
   *       break;
   *     case 'result':
   *       console.log('Final result:', message.result);
   *       break;
   *     case 'error':
   *       console.error('Error:', message.error);
   *       break;
   *   }
   * }
   * ```
   *
   * @experimental Use `client.experimental.tasks.requestStream()` to access this method.
   */
  async *requestStream(request, resultSchema, options) {
    let { task } = options ?? {};
    if (!task) {
      try {
        yield { type: "result", result: await this.request(request, resultSchema, options) };
      } catch (error2) {
        yield {
          type: "error",
          error: error2 instanceof McpError ? error2 : new McpError(ErrorCode.InternalError, String(error2))
        };
      }
      return;
    }
    let taskId;
    try {
      let createResult = await this.request(request, CreateTaskResultSchema, options);
      if (createResult.task)
        taskId = createResult.task.taskId, yield { type: "taskCreated", task: createResult.task };
      else
        throw new McpError(ErrorCode.InternalError, "Task creation did not return a task");
      for (; ; ) {
        let task2 = await this.getTask({ taskId }, options);
        if (yield { type: "taskStatus", task: task2 }, isTerminal(task2.status)) {
          task2.status === "completed" ? yield { type: "result", result: await this.getTaskResult({ taskId }, resultSchema, options) } : task2.status === "failed" ? yield {
            type: "error",
            error: new McpError(ErrorCode.InternalError, `Task ${taskId} failed`)
          } : task2.status === "cancelled" && (yield {
            type: "error",
            error: new McpError(ErrorCode.InternalError, `Task ${taskId} was cancelled`)
          });
          return;
        }
        if (task2.status === "input_required") {
          yield { type: "result", result: await this.getTaskResult({ taskId }, resultSchema, options) };
          return;
        }
        let pollInterval = task2.pollInterval ?? this._options?.defaultTaskPollInterval ?? 1e3;
        await new Promise((resolve) => setTimeout(resolve, pollInterval)), options?.signal?.throwIfAborted();
      }
    } catch (error2) {
      yield {
        type: "error",
        error: error2 instanceof McpError ? error2 : new McpError(ErrorCode.InternalError, String(error2))
      };
    }
  }
  /**
   * Sends a request and waits for a response.
   *
   * Do not use this method to emit notifications! Use notification() instead.
   */
  request(request, resultSchema, options) {
    let { relatedRequestId, resumptionToken, onresumptiontoken, task, relatedTask } = options ?? {};
    return new Promise((resolve, reject) => {
      let earlyReject = (error2) => {
        reject(error2);
      };
      if (!this._transport) {
        earlyReject(new Error("Not connected"));
        return;
      }
      if (this._options?.enforceStrictCapabilities === !0)
        try {
          this.assertCapabilityForMethod(request.method), task && this.assertTaskCapability(request.method);
        } catch (e) {
          earlyReject(e);
          return;
        }
      options?.signal?.throwIfAborted();
      let messageId = this._requestMessageId++, jsonrpcRequest = {
        ...request,
        jsonrpc: "2.0",
        id: messageId
      };
      options?.onprogress && (this._progressHandlers.set(messageId, options.onprogress), jsonrpcRequest.params = {
        ...request.params,
        _meta: {
          ...request.params?._meta || {},
          progressToken: messageId
        }
      }), task && (jsonrpcRequest.params = {
        ...jsonrpcRequest.params,
        task
      }), relatedTask && (jsonrpcRequest.params = {
        ...jsonrpcRequest.params,
        _meta: {
          ...jsonrpcRequest.params?._meta || {},
          [RELATED_TASK_META_KEY]: relatedTask
        }
      });
      let cancel = (reason) => {
        this._responseHandlers.delete(messageId), this._progressHandlers.delete(messageId), this._cleanupTimeout(messageId), this._transport?.send({
          jsonrpc: "2.0",
          method: "notifications/cancelled",
          params: {
            requestId: messageId,
            reason: String(reason)
          }
        }, { relatedRequestId, resumptionToken, onresumptiontoken }).catch((error3) => this._onerror(new Error(`Failed to send cancellation: ${error3}`)));
        let error2 = reason instanceof McpError ? reason : new McpError(ErrorCode.RequestTimeout, String(reason));
        reject(error2);
      };
      this._responseHandlers.set(messageId, (response) => {
        if (!options?.signal?.aborted) {
          if (response instanceof Error)
            return reject(response);
          try {
            let parseResult = safeParse2(resultSchema, response.result);
            parseResult.success ? resolve(parseResult.data) : reject(parseResult.error);
          } catch (error2) {
            reject(error2);
          }
        }
      }), options?.signal?.addEventListener("abort", () => {
        cancel(options?.signal?.reason);
      });
      let timeout = options?.timeout ?? DEFAULT_REQUEST_TIMEOUT_MSEC, timeoutHandler = () => cancel(McpError.fromError(ErrorCode.RequestTimeout, "Request timed out", { timeout }));
      this._setupTimeout(messageId, timeout, options?.maxTotalTimeout, timeoutHandler, options?.resetTimeoutOnProgress ?? !1);
      let relatedTaskId = relatedTask?.taskId;
      if (relatedTaskId) {
        let responseResolver = (response) => {
          let handler = this._responseHandlers.get(messageId);
          handler ? handler(response) : this._onerror(new Error(`Response handler missing for side-channeled request ${messageId}`));
        };
        this._requestResolvers.set(messageId, responseResolver), this._enqueueTaskMessage(relatedTaskId, {
          type: "request",
          message: jsonrpcRequest,
          timestamp: Date.now()
        }).catch((error2) => {
          this._cleanupTimeout(messageId), reject(error2);
        });
      } else
        this._transport.send(jsonrpcRequest, { relatedRequestId, resumptionToken, onresumptiontoken }).catch((error2) => {
          this._cleanupTimeout(messageId), reject(error2);
        });
    });
  }
  /**
   * Gets the current status of a task.
   *
   * @experimental Use `client.experimental.tasks.getTask()` to access this method.
   */
  async getTask(params, options) {
    return this.request({ method: "tasks/get", params }, GetTaskResultSchema, options);
  }
  /**
   * Retrieves the result of a completed task.
   *
   * @experimental Use `client.experimental.tasks.getTaskResult()` to access this method.
   */
  async getTaskResult(params, resultSchema, options) {
    return this.request({ method: "tasks/result", params }, resultSchema, options);
  }
  /**
   * Lists tasks, optionally starting from a pagination cursor.
   *
   * @experimental Use `client.experimental.tasks.listTasks()` to access this method.
   */
  async listTasks(params, options) {
    return this.request({ method: "tasks/list", params }, ListTasksResultSchema, options);
  }
  /**
   * Cancels a specific task.
   *
   * @experimental Use `client.experimental.tasks.cancelTask()` to access this method.
   */
  async cancelTask(params, options) {
    return this.request({ method: "tasks/cancel", params }, CancelTaskResultSchema, options);
  }
  /**
   * Emits a notification, which is a one-way message that does not expect a response.
   */
  async notification(notification, options) {
    if (!this._transport)
      throw new Error("Not connected");
    this.assertNotificationCapability(notification.method);
    let relatedTaskId = options?.relatedTask?.taskId;
    if (relatedTaskId) {
      let jsonrpcNotification2 = {
        ...notification,
        jsonrpc: "2.0",
        params: {
          ...notification.params,
          _meta: {
            ...notification.params?._meta || {},
            [RELATED_TASK_META_KEY]: options.relatedTask
          }
        }
      };
      await this._enqueueTaskMessage(relatedTaskId, {
        type: "notification",
        message: jsonrpcNotification2,
        timestamp: Date.now()
      });
      return;
    }
    if ((this._options?.debouncedNotificationMethods ?? []).includes(notification.method) && !notification.params && !options?.relatedRequestId && !options?.relatedTask) {
      if (this._pendingDebouncedNotifications.has(notification.method))
        return;
      this._pendingDebouncedNotifications.add(notification.method), Promise.resolve().then(() => {
        if (this._pendingDebouncedNotifications.delete(notification.method), !this._transport)
          return;
        let jsonrpcNotification2 = {
          ...notification,
          jsonrpc: "2.0"
        };
        options?.relatedTask && (jsonrpcNotification2 = {
          ...jsonrpcNotification2,
          params: {
            ...jsonrpcNotification2.params,
            _meta: {
              ...jsonrpcNotification2.params?._meta || {},
              [RELATED_TASK_META_KEY]: options.relatedTask
            }
          }
        }), this._transport?.send(jsonrpcNotification2, options).catch((error2) => this._onerror(error2));
      });
      return;
    }
    let jsonrpcNotification = {
      ...notification,
      jsonrpc: "2.0"
    };
    options?.relatedTask && (jsonrpcNotification = {
      ...jsonrpcNotification,
      params: {
        ...jsonrpcNotification.params,
        _meta: {
          ...jsonrpcNotification.params?._meta || {},
          [RELATED_TASK_META_KEY]: options.relatedTask
        }
      }
    }), await this._transport.send(jsonrpcNotification, options);
  }
  /**
   * Registers a handler to invoke when this protocol object receives a request with the given method.
   *
   * Note that this will replace any previous request handler for the same method.
   */
  setRequestHandler(requestSchema, handler) {
    let method = getMethodLiteral(requestSchema);
    this.assertRequestHandlerCapability(method), this._requestHandlers.set(method, (request, extra) => {
      let parsed = parseWithCompat(requestSchema, request);
      return Promise.resolve(handler(parsed, extra));
    });
  }
  /**
   * Removes the request handler for the given method.
   */
  removeRequestHandler(method) {
    this._requestHandlers.delete(method);
  }
  /**
   * Asserts that a request handler has not already been set for the given method, in preparation for a new one being automatically installed.
   */
  assertCanSetRequestHandler(method) {
    if (this._requestHandlers.has(method))
      throw new Error(`A request handler for ${method} already exists, which would be overridden`);
  }
  /**
   * Registers a handler to invoke when this protocol object receives a notification with the given method.
   *
   * Note that this will replace any previous notification handler for the same method.
   */
  setNotificationHandler(notificationSchema, handler) {
    let method = getMethodLiteral(notificationSchema);
    this._notificationHandlers.set(method, (notification) => {
      let parsed = parseWithCompat(notificationSchema, notification);
      return Promise.resolve(handler(parsed));
    });
  }
  /**
   * Removes the notification handler for the given method.
   */
  removeNotificationHandler(method) {
    this._notificationHandlers.delete(method);
  }
  /**
   * Cleans up the progress handler associated with a task.
   * This should be called when a task reaches a terminal status.
   */
  _cleanupTaskProgressHandler(taskId) {
    let progressToken = this._taskProgressTokens.get(taskId);
    progressToken !== void 0 && (this._progressHandlers.delete(progressToken), this._taskProgressTokens.delete(taskId));
  }
  /**
   * Enqueues a task-related message for side-channel delivery via tasks/result.
   * @param taskId The task ID to associate the message with
   * @param message The message to enqueue
   * @param sessionId Optional session ID for binding the operation to a specific session
   * @throws Error if taskStore is not configured or if enqueue fails (e.g., queue overflow)
   *
   * Note: If enqueue fails, it's the TaskMessageQueue implementation's responsibility to handle
   * the error appropriately (e.g., by failing the task, logging, etc.). The Protocol layer
   * simply propagates the error.
   */
  async _enqueueTaskMessage(taskId, message, sessionId) {
    if (!this._taskStore || !this._taskMessageQueue)
      throw new Error("Cannot enqueue task message: taskStore and taskMessageQueue are not configured");
    let maxQueueSize = this._options?.maxTaskQueueSize;
    await this._taskMessageQueue.enqueue(taskId, message, sessionId ?? this._transport?.sessionId, maxQueueSize);
  }
  /**
   * Clears the message queue for a task and rejects any pending request resolvers.
   * @param taskId The task ID whose queue should be cleared
   * @param sessionId Optional session ID for binding the operation to a specific session
   */
  async _clearTaskQueue(taskId, sessionId) {
    if (this._taskMessageQueue) {
      let messages = await this._taskMessageQueue.dequeueAll(taskId, sessionId);
      for (let message of messages)
        if (message.type === "request" && isJSONRPCRequest(message.message)) {
          let requestId = message.message.id, resolver = this._requestResolvers.get(requestId);
          resolver ? (resolver(new McpError(ErrorCode.InternalError, "Task cancelled or completed")), this._requestResolvers.delete(requestId)) : this._onerror(new Error(`Resolver missing for request ${requestId} during task ${taskId} cleanup`));
        }
    }
  }
  /**
   * Waits for a task update (new messages or status change) with abort signal support.
   * Uses polling to check for updates at the task's configured poll interval.
   * @param taskId The task ID to wait for
   * @param signal Abort signal to cancel the wait
   * @param sessionId Session of the request that waits, passed to the task store
   * @returns Promise that resolves when an update occurs or rejects if aborted
   */
  async _waitForTaskUpdate(taskId, signal, sessionId) {
    let interval = this._options?.defaultTaskPollInterval ?? 1e3;
    try {
      let task = await this._taskStore?.getTask(taskId, sessionId);
      task?.pollInterval && (interval = task.pollInterval);
    } catch {
    }
    return new Promise((resolve, reject) => {
      if (signal.aborted) {
        reject(new McpError(ErrorCode.InvalidRequest, "Request cancelled"));
        return;
      }
      let timeoutId = setTimeout(resolve, interval);
      signal.addEventListener("abort", () => {
        clearTimeout(timeoutId), reject(new McpError(ErrorCode.InvalidRequest, "Request cancelled"));
      }, { once: !0 });
    });
  }
  requestTaskStore(request, sessionId) {
    let taskStore = this._taskStore;
    if (!taskStore)
      throw new Error("No task store configured");
    return {
      createTask: async (taskParams) => {
        if (!request)
          throw new Error("No request provided");
        return await taskStore.createTask(taskParams, request.id, {
          method: request.method,
          params: request.params
        }, sessionId);
      },
      getTask: async (taskId) => {
        let task = await taskStore.getTask(taskId, sessionId);
        if (!task)
          throw new McpError(ErrorCode.InvalidParams, "Failed to retrieve task: Task not found");
        return task;
      },
      storeTaskResult: async (taskId, status, result) => {
        await taskStore.storeTaskResult(taskId, status, result, sessionId);
        let task = await taskStore.getTask(taskId, sessionId);
        if (task) {
          let notification = TaskStatusNotificationSchema.parse({
            method: "notifications/tasks/status",
            params: task
          });
          await this.notification(notification), isTerminal(task.status) && this._cleanupTaskProgressHandler(taskId);
        }
      },
      getTaskResult: (taskId) => taskStore.getTaskResult(taskId, sessionId),
      updateTaskStatus: async (taskId, status, statusMessage) => {
        let task = await taskStore.getTask(taskId, sessionId);
        if (!task)
          throw new McpError(ErrorCode.InvalidParams, `Task "${taskId}" not found - it may have been cleaned up`);
        if (isTerminal(task.status))
          throw new McpError(ErrorCode.InvalidParams, `Cannot update task "${taskId}" from terminal status "${task.status}" to "${status}". Terminal states (completed, failed, cancelled) cannot transition to other states.`);
        await taskStore.updateTaskStatus(taskId, status, statusMessage, sessionId);
        let updatedTask = await taskStore.getTask(taskId, sessionId);
        if (updatedTask) {
          let notification = TaskStatusNotificationSchema.parse({
            method: "notifications/tasks/status",
            params: updatedTask
          });
          await this.notification(notification), isTerminal(updatedTask.status) && this._cleanupTaskProgressHandler(taskId);
        }
      },
      listTasks: (cursor) => taskStore.listTasks(cursor, sessionId)
    };
  }
};
function isPlainObject2(value) {
  return value !== null && typeof value == "object" && !Array.isArray(value);
}
function mergeCapabilities(base, additional) {
  let result = { ...base };
  for (let key in additional) {
    let k = key, addValue = additional[k];
    if (addValue === void 0)
      continue;
    let baseValue = result[k];
    isPlainObject2(baseValue) && isPlainObject2(addValue) ? result[k] = { ...baseValue, ...addValue } : result[k] = addValue;
  }
  return result;
}

// node_modules/@modelcontextprotocol/sdk/dist/esm/validation/ajv-provider.js
var import_ajv = __toESM(require_ajv(), 1), import_ajv_formats = __toESM(require_dist(), 1);
function createDefaultAjvInstance() {
  let ajv = new import_ajv.default({
    strict: !1,
    validateFormats: !0,
    validateSchema: !1,
    allErrors: !0
  });
  return (0, import_ajv_formats.default)(ajv), ajv;
}
var AjvJsonSchemaValidator = class {
  /**
   * Create an AJV validator
   *
   * @param ajv - Optional pre-configured AJV instance. If not provided, a default instance will be created.
   *
   * @example
   * ```typescript
   * // Use default configuration (recommended for most cases)
   * import { AjvJsonSchemaValidator } from '@modelcontextprotocol/sdk/validation/ajv';
   * const validator = new AjvJsonSchemaValidator();
   *
   * // Or provide custom AJV instance for advanced configuration
   * import { Ajv } from 'ajv';
   * import addFormats from 'ajv-formats';
   *
   * const ajv = new Ajv({ validateFormats: true });
   * addFormats(ajv);
   * const validator = new AjvJsonSchemaValidator(ajv);
   * ```
   */
  constructor(ajv) {
    this._ajv = ajv ?? createDefaultAjvInstance();
  }
  /**
   * Create a validator for the given JSON Schema
   *
   * The validator is compiled once and can be reused multiple times.
   * If the schema has an $id, it will be cached by AJV automatically.
   *
   * @param schema - Standard JSON Schema object
   * @returns A validator function that validates input data
   */
  getValidator(schema) {
    let ajvValidator = "$id" in schema && typeof schema.$id == "string" ? this._ajv.getSchema(schema.$id) ?? this._ajv.compile(schema) : this._ajv.compile(schema);
    return (input) => ajvValidator(input) ? {
      valid: !0,
      data: input,
      errorMessage: void 0
    } : {
      valid: !1,
      data: void 0,
      errorMessage: this._ajv.errorsText(ajvValidator.errors)
    };
  }
};

// node_modules/@modelcontextprotocol/sdk/dist/esm/experimental/tasks/server.js
var ExperimentalServerTasks = class {
  constructor(_server) {
    this._server = _server;
  }
  /**
   * Sends a request and returns an AsyncGenerator that yields response messages.
   * The generator is guaranteed to end with either a 'result' or 'error' message.
   *
   * This method provides streaming access to request processing, allowing you to
   * observe intermediate task status updates for task-augmented requests.
   *
   * @param request - The request to send
   * @param resultSchema - Zod schema for validating the result
   * @param options - Optional request options (timeout, signal, task creation params, etc.)
   * @returns AsyncGenerator that yields ResponseMessage objects
   *
   * @experimental
   */
  requestStream(request, resultSchema, options) {
    return this._server.requestStream(request, resultSchema, options);
  }
  /**
   * Sends a sampling request and returns an AsyncGenerator that yields response messages.
   * The generator is guaranteed to end with either a 'result' or 'error' message.
   *
   * For task-augmented requests, yields 'taskCreated' and 'taskStatus' messages
   * before the final result.
   *
   * @example
   * ```typescript
   * const stream = server.experimental.tasks.createMessageStream({
   *     messages: [{ role: 'user', content: { type: 'text', text: 'Hello' } }],
   *     maxTokens: 100
   * }, {
   *     onprogress: (progress) => {
   *         // Handle streaming tokens via progress notifications
   *         console.log('Progress:', progress.message);
   *     }
   * });
   *
   * for await (const message of stream) {
   *     switch (message.type) {
   *         case 'taskCreated':
   *             console.log('Task created:', message.task.taskId);
   *             break;
   *         case 'taskStatus':
   *             console.log('Task status:', message.task.status);
   *             break;
   *         case 'result':
   *             console.log('Final result:', message.result);
   *             break;
   *         case 'error':
   *             console.error('Error:', message.error);
   *             break;
   *     }
   * }
   * ```
   *
   * @param params - The sampling request parameters
   * @param options - Optional request options (timeout, signal, task creation params, onprogress, etc.)
   * @returns AsyncGenerator that yields ResponseMessage objects
   *
   * @experimental
   */
  createMessageStream(params, options) {
    let clientCapabilities = this._server.getClientCapabilities();
    if ((params.tools || params.toolChoice) && !clientCapabilities?.sampling?.tools)
      throw new Error("Client does not support sampling tools capability.");
    if (params.messages.length > 0) {
      let lastMessage = params.messages[params.messages.length - 1], lastContent = Array.isArray(lastMessage.content) ? lastMessage.content : [lastMessage.content], hasToolResults = lastContent.some((c) => c.type === "tool_result"), previousMessage = params.messages.length > 1 ? params.messages[params.messages.length - 2] : void 0, previousContent = previousMessage ? Array.isArray(previousMessage.content) ? previousMessage.content : [previousMessage.content] : [], hasPreviousToolUse = previousContent.some((c) => c.type === "tool_use");
      if (hasToolResults) {
        if (lastContent.some((c) => c.type !== "tool_result"))
          throw new Error("The last message must contain only tool_result content if any is present");
        if (!hasPreviousToolUse)
          throw new Error("tool_result blocks are not matching any tool_use from the previous message");
      }
      if (hasPreviousToolUse) {
        let toolUseIds = new Set(previousContent.filter((c) => c.type === "tool_use").map((c) => c.id)), toolResultIds = new Set(lastContent.filter((c) => c.type === "tool_result").map((c) => c.toolUseId));
        if (toolUseIds.size !== toolResultIds.size || ![...toolUseIds].every((id) => toolResultIds.has(id)))
          throw new Error("ids of tool_result blocks and tool_use blocks from previous message do not match");
      }
    }
    return this.requestStream({
      method: "sampling/createMessage",
      params
    }, CreateMessageResultSchema, options);
  }
  /**
   * Sends an elicitation request and returns an AsyncGenerator that yields response messages.
   * The generator is guaranteed to end with either a 'result' or 'error' message.
   *
   * For task-augmented requests (especially URL-based elicitation), yields 'taskCreated'
   * and 'taskStatus' messages before the final result.
   *
   * @example
   * ```typescript
   * const stream = server.experimental.tasks.elicitInputStream({
   *     mode: 'url',
   *     message: 'Please authenticate',
   *     elicitationId: 'auth-123',
   *     url: 'https://example.com/auth'
   * }, {
   *     task: { ttl: 300000 } // Task-augmented for long-running auth flow
   * });
   *
   * for await (const message of stream) {
   *     switch (message.type) {
   *         case 'taskCreated':
   *             console.log('Task created:', message.task.taskId);
   *             break;
   *         case 'taskStatus':
   *             console.log('Task status:', message.task.status);
   *             break;
   *         case 'result':
   *             console.log('User action:', message.result.action);
   *             break;
   *         case 'error':
   *             console.error('Error:', message.error);
   *             break;
   *     }
   * }
   * ```
   *
   * @param params - The elicitation request parameters
   * @param options - Optional request options (timeout, signal, task creation params, etc.)
   * @returns AsyncGenerator that yields ResponseMessage objects
   *
   * @experimental
   */
  elicitInputStream(params, options) {
    let clientCapabilities = this._server.getClientCapabilities(), mode = params.mode ?? "form";
    switch (mode) {
      case "url": {
        if (!clientCapabilities?.elicitation?.url)
          throw new Error("Client does not support url elicitation.");
        break;
      }
      case "form": {
        if (!clientCapabilities?.elicitation?.form)
          throw new Error("Client does not support form elicitation.");
        break;
      }
    }
    let normalizedParams = mode === "form" && params.mode === void 0 ? { ...params, mode: "form" } : params;
    return this.requestStream({
      method: "elicitation/create",
      params: normalizedParams
    }, ElicitResultSchema, options);
  }
  /**
   * Gets the current status of a task.
   *
   * @param taskId - The task identifier
   * @param options - Optional request options
   * @returns The task status
   *
   * @experimental
   */
  async getTask(taskId, options) {
    return this._server.getTask({ taskId }, options);
  }
  /**
   * Retrieves the result of a completed task.
   *
   * @param taskId - The task identifier
   * @param resultSchema - Zod schema for validating the result
   * @param options - Optional request options
   * @returns The task result
   *
   * @experimental
   */
  async getTaskResult(taskId, resultSchema, options) {
    return this._server.getTaskResult({ taskId }, resultSchema, options);
  }
  /**
   * Lists tasks with optional pagination.
   *
   * @param cursor - Optional pagination cursor
   * @param options - Optional request options
   * @returns List of tasks with optional next cursor
   *
   * @experimental
   */
  async listTasks(cursor, options) {
    return this._server.listTasks(cursor ? { cursor } : void 0, options);
  }
  /**
   * Cancels a running task.
   *
   * @param taskId - The task identifier
   * @param options - Optional request options
   *
   * @experimental
   */
  async cancelTask(taskId, options) {
    return this._server.cancelTask({ taskId }, options);
  }
};

// node_modules/@modelcontextprotocol/sdk/dist/esm/experimental/tasks/helpers.js
function assertToolsCallTaskCapability(requests, method, entityName) {
  if (!requests)
    throw new Error(`${entityName} does not support task creation (required for ${method})`);
  switch (method) {
    case "tools/call":
      if (!requests.tools?.call)
        throw new Error(`${entityName} does not support task creation for tools/call (required for ${method})`);
      break;
    default:
      break;
  }
}
function assertClientRequestTaskCapability(requests, method, entityName) {
  if (!requests)
    throw new Error(`${entityName} does not support task creation (required for ${method})`);
  switch (method) {
    case "sampling/createMessage":
      if (!requests.sampling?.createMessage)
        throw new Error(`${entityName} does not support task creation for sampling/createMessage (required for ${method})`);
      break;
    case "elicitation/create":
      if (!requests.elicitation?.create)
        throw new Error(`${entityName} does not support task creation for elicitation/create (required for ${method})`);
      break;
    default:
      break;
  }
}

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/index.js
var Server = class extends Protocol {
  /**
   * Initializes this server with the given name and version information.
   */
  constructor(_serverInfo, options) {
    super(options), this._serverInfo = _serverInfo, this._loggingLevels = /* @__PURE__ */ new Map(), this.LOG_LEVEL_SEVERITY = new Map(LoggingLevelSchema.options.map((level, index) => [level, index])), this.isMessageIgnored = (level, sessionId) => {
      let currentLevel = this._loggingLevels.get(sessionId);
      return currentLevel ? this.LOG_LEVEL_SEVERITY.get(level) < this.LOG_LEVEL_SEVERITY.get(currentLevel) : !1;
    }, this._capabilities = options?.capabilities ?? {}, this._instructions = options?.instructions, this._jsonSchemaValidator = options?.jsonSchemaValidator ?? new AjvJsonSchemaValidator(), this.setRequestHandler(InitializeRequestSchema, (request) => this._oninitialize(request)), this.setNotificationHandler(InitializedNotificationSchema, () => this.oninitialized?.()), this._capabilities.logging && this.setRequestHandler(SetLevelRequestSchema, async (request, extra) => {
      let transportSessionId = extra.sessionId || extra.requestInfo?.headers["mcp-session-id"] || void 0, { level } = request.params, parseResult = LoggingLevelSchema.safeParse(level);
      return parseResult.success && this._loggingLevels.set(transportSessionId, parseResult.data), {};
    });
  }
  /**
   * Access experimental features.
   *
   * WARNING: These APIs are experimental and may change without notice.
   *
   * @experimental
   */
  get experimental() {
    return this._experimental || (this._experimental = {
      tasks: new ExperimentalServerTasks(this)
    }), this._experimental;
  }
  /**
   * Registers new capabilities. This can only be called before connecting to a transport.
   *
   * The new capabilities will be merged with any existing capabilities previously given (e.g., at initialization).
   */
  registerCapabilities(capabilities) {
    if (this.transport)
      throw new Error("Cannot register capabilities after connecting to transport");
    this._capabilities = mergeCapabilities(this._capabilities, capabilities);
  }
  /**
   * Override request handler registration to enforce server-side validation for tools/call.
   */
  setRequestHandler(requestSchema, handler) {
    let methodSchema = getObjectShape(requestSchema)?.method;
    if (!methodSchema)
      throw new Error("Schema is missing a method literal");
    let methodValue = getLiteralValue(methodSchema);
    if (typeof methodValue != "string")
      throw new Error("Schema method literal must be a string");
    if (methodValue === "tools/call") {
      let wrappedHandler = async (request, extra) => {
        let validatedRequest = safeParse2(CallToolRequestSchema, request);
        if (!validatedRequest.success) {
          let errorMessage = validatedRequest.error instanceof Error ? validatedRequest.error.message : String(validatedRequest.error);
          throw new McpError(ErrorCode.InvalidParams, `Invalid tools/call request: ${errorMessage}`);
        }
        let { params } = validatedRequest.data, result = await Promise.resolve(handler(request, extra));
        if (params.task) {
          let taskValidationResult = safeParse2(CreateTaskResultSchema, result);
          if (!taskValidationResult.success) {
            let errorMessage = taskValidationResult.error instanceof Error ? taskValidationResult.error.message : String(taskValidationResult.error);
            throw new McpError(ErrorCode.InvalidParams, `Invalid task creation result: ${errorMessage}`);
          }
          return taskValidationResult.data;
        }
        let validationResult = safeParse2(CallToolResultSchema, result);
        if (!validationResult.success) {
          let errorMessage = validationResult.error instanceof Error ? validationResult.error.message : String(validationResult.error);
          throw new McpError(ErrorCode.InvalidParams, `Invalid tools/call result: ${errorMessage}`);
        }
        return validationResult.data;
      };
      return super.setRequestHandler(requestSchema, wrappedHandler);
    }
    return super.setRequestHandler(requestSchema, handler);
  }
  assertCapabilityForMethod(method) {
    switch (method) {
      case "sampling/createMessage":
        if (!this._clientCapabilities?.sampling)
          throw new Error(`Client does not support sampling (required for ${method})`);
        break;
      case "elicitation/create":
        if (!this._clientCapabilities?.elicitation)
          throw new Error(`Client does not support elicitation (required for ${method})`);
        break;
      case "roots/list":
        if (!this._clientCapabilities?.roots)
          throw new Error(`Client does not support listing roots (required for ${method})`);
        break;
      case "ping":
        break;
    }
  }
  assertNotificationCapability(method) {
    switch (method) {
      case "notifications/message":
        if (!this._capabilities.logging)
          throw new Error(`Server does not support logging (required for ${method})`);
        break;
      case "notifications/resources/updated":
      case "notifications/resources/list_changed":
        if (!this._capabilities.resources)
          throw new Error(`Server does not support notifying about resources (required for ${method})`);
        break;
      case "notifications/tools/list_changed":
        if (!this._capabilities.tools)
          throw new Error(`Server does not support notifying of tool list changes (required for ${method})`);
        break;
      case "notifications/prompts/list_changed":
        if (!this._capabilities.prompts)
          throw new Error(`Server does not support notifying of prompt list changes (required for ${method})`);
        break;
      case "notifications/elicitation/complete":
        if (!this._clientCapabilities?.elicitation?.url)
          throw new Error(`Client does not support URL elicitation (required for ${method})`);
        break;
      case "notifications/cancelled":
        break;
      case "notifications/progress":
        break;
    }
  }
  assertRequestHandlerCapability(method) {
    if (this._capabilities)
      switch (method) {
        case "completion/complete":
          if (!this._capabilities.completions)
            throw new Error(`Server does not support completions (required for ${method})`);
          break;
        case "logging/setLevel":
          if (!this._capabilities.logging)
            throw new Error(`Server does not support logging (required for ${method})`);
          break;
        case "prompts/get":
        case "prompts/list":
          if (!this._capabilities.prompts)
            throw new Error(`Server does not support prompts (required for ${method})`);
          break;
        case "resources/list":
        case "resources/templates/list":
        case "resources/read":
          if (!this._capabilities.resources)
            throw new Error(`Server does not support resources (required for ${method})`);
          break;
        case "tools/call":
        case "tools/list":
          if (!this._capabilities.tools)
            throw new Error(`Server does not support tools (required for ${method})`);
          break;
        case "tasks/get":
        case "tasks/list":
        case "tasks/result":
        case "tasks/cancel":
          if (!this._capabilities.tasks)
            throw new Error(`Server does not support tasks capability (required for ${method})`);
          break;
        case "ping":
        case "initialize":
          break;
      }
  }
  assertTaskCapability(method) {
    assertClientRequestTaskCapability(this._clientCapabilities?.tasks?.requests, method, "Client");
  }
  assertTaskHandlerCapability(method) {
    this._capabilities && assertToolsCallTaskCapability(this._capabilities.tasks?.requests, method, "Server");
  }
  async _oninitialize(request) {
    let requestedVersion = request.params.protocolVersion;
    return this._clientCapabilities = request.params.capabilities, this._clientVersion = request.params.clientInfo, {
      protocolVersion: SUPPORTED_PROTOCOL_VERSIONS.includes(requestedVersion) ? requestedVersion : LATEST_PROTOCOL_VERSION,
      capabilities: this.getCapabilities(),
      serverInfo: this._serverInfo,
      ...this._instructions && { instructions: this._instructions }
    };
  }
  /**
   * After initialization has completed, this will be populated with the client's reported capabilities.
   */
  getClientCapabilities() {
    return this._clientCapabilities;
  }
  /**
   * After initialization has completed, this will be populated with information about the client's name and version.
   */
  getClientVersion() {
    return this._clientVersion;
  }
  getCapabilities() {
    return this._capabilities;
  }
  async ping() {
    return this.request({ method: "ping" }, EmptyResultSchema);
  }
  // Implementation
  async createMessage(params, options) {
    if ((params.tools || params.toolChoice) && !this._clientCapabilities?.sampling?.tools)
      throw new Error("Client does not support sampling tools capability.");
    if (params.messages.length > 0) {
      let lastMessage = params.messages[params.messages.length - 1], lastContent = Array.isArray(lastMessage.content) ? lastMessage.content : [lastMessage.content], hasToolResults = lastContent.some((c) => c.type === "tool_result"), previousMessage = params.messages.length > 1 ? params.messages[params.messages.length - 2] : void 0, previousContent = previousMessage ? Array.isArray(previousMessage.content) ? previousMessage.content : [previousMessage.content] : [], hasPreviousToolUse = previousContent.some((c) => c.type === "tool_use");
      if (hasToolResults) {
        if (lastContent.some((c) => c.type !== "tool_result"))
          throw new Error("The last message must contain only tool_result content if any is present");
        if (!hasPreviousToolUse)
          throw new Error("tool_result blocks are not matching any tool_use from the previous message");
      }
      if (hasPreviousToolUse) {
        let toolUseIds = new Set(previousContent.filter((c) => c.type === "tool_use").map((c) => c.id)), toolResultIds = new Set(lastContent.filter((c) => c.type === "tool_result").map((c) => c.toolUseId));
        if (toolUseIds.size !== toolResultIds.size || ![...toolUseIds].every((id) => toolResultIds.has(id)))
          throw new Error("ids of tool_result blocks and tool_use blocks from previous message do not match");
      }
    }
    return params.tools ? this.request({ method: "sampling/createMessage", params }, CreateMessageResultWithToolsSchema, options) : this.request({ method: "sampling/createMessage", params }, CreateMessageResultSchema, options);
  }
  /**
   * Creates an elicitation request for the given parameters.
   * For backwards compatibility, `mode` may be omitted for form requests and will default to `'form'`.
   * @param params The parameters for the elicitation request.
   * @param options Optional request options.
   * @returns The result of the elicitation request.
   */
  async elicitInput(params, options) {
    switch (params.mode ?? "form") {
      case "url": {
        if (!this._clientCapabilities?.elicitation?.url)
          throw new Error("Client does not support url elicitation.");
        let urlParams = params;
        return this.request({ method: "elicitation/create", params: urlParams }, ElicitResultSchema, options);
      }
      case "form": {
        if (!this._clientCapabilities?.elicitation?.form)
          throw new Error("Client does not support form elicitation.");
        let formParams = params.mode === "form" ? params : { ...params, mode: "form" }, result = await this.request({ method: "elicitation/create", params: formParams }, ElicitResultSchema, options);
        if (result.action === "accept" && result.content && formParams.requestedSchema)
          try {
            let validationResult = this._jsonSchemaValidator.getValidator(formParams.requestedSchema)(result.content);
            if (!validationResult.valid)
              throw new McpError(ErrorCode.InvalidParams, `Elicitation response content does not match requested schema: ${validationResult.errorMessage}`);
          } catch (error2) {
            throw error2 instanceof McpError ? error2 : new McpError(ErrorCode.InternalError, `Error validating elicitation response: ${error2 instanceof Error ? error2.message : String(error2)}`);
          }
        return result;
      }
    }
  }
  /**
   * Creates a reusable callback that, when invoked, will send a `notifications/elicitation/complete`
   * notification for the specified elicitation ID.
   *
   * @param elicitationId The ID of the elicitation to mark as complete.
   * @param options Optional notification options. Useful when the completion notification should be related to a prior request.
   * @returns A function that emits the completion notification when awaited.
   */
  createElicitationCompletionNotifier(elicitationId, options) {
    if (!this._clientCapabilities?.elicitation?.url)
      throw new Error("Client does not support URL elicitation (required for notifications/elicitation/complete)");
    return () => this.notification({
      method: "notifications/elicitation/complete",
      params: {
        elicitationId
      }
    }, options);
  }
  async listRoots(params, options) {
    return this.request({ method: "roots/list", params }, ListRootsResultSchema, options);
  }
  /**
   * Sends a logging message to the client, if connected.
   * Note: You only need to send the parameters object, not the entire JSON RPC message
   * @see LoggingMessageNotification
   * @param params
   * @param sessionId optional for stateless and backward compatibility
   */
  async sendLoggingMessage(params, sessionId) {
    if (this._capabilities.logging && !this.isMessageIgnored(params.level, sessionId))
      return this.notification({ method: "notifications/message", params });
  }
  async sendResourceUpdated(params) {
    return this.notification({
      method: "notifications/resources/updated",
      params
    });
  }
  async sendResourceListChanged() {
    return this.notification({
      method: "notifications/resources/list_changed"
    });
  }
  async sendToolListChanged() {
    return this.notification({ method: "notifications/tools/list_changed" });
  }
  async sendPromptListChanged() {
    return this.notification({ method: "notifications/prompts/list_changed" });
  }
};

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/completable.js
var COMPLETABLE_SYMBOL = Symbol.for("mcp.completable");
function isCompletable(schema) {
  return !!schema && typeof schema == "object" && COMPLETABLE_SYMBOL in schema;
}
function getCompleter(schema) {
  return schema[COMPLETABLE_SYMBOL]?.complete;
}
var McpZodTypeKind;
(function(McpZodTypeKind2) {
  McpZodTypeKind2.Completable = "McpCompletable";
})(McpZodTypeKind || (McpZodTypeKind = {}));

// node_modules/@modelcontextprotocol/sdk/dist/esm/shared/toolNameValidation.js
var TOOL_NAME_REGEX = /^[A-Za-z0-9._-]{1,128}$/;
function validateToolName(name) {
  let warnings = [];
  if (name.length === 0)
    return {
      isValid: !1,
      warnings: ["Tool name cannot be empty"]
    };
  if (name.length > 128)
    return {
      isValid: !1,
      warnings: [`Tool name exceeds maximum length of 128 characters (current: ${name.length})`]
    };
  if (name.includes(" ") && warnings.push("Tool name contains spaces, which may cause parsing issues"), name.includes(",") && warnings.push("Tool name contains commas, which may cause parsing issues"), (name.startsWith("-") || name.endsWith("-")) && warnings.push("Tool name starts or ends with a dash, which may cause parsing issues in some contexts"), (name.startsWith(".") || name.endsWith(".")) && warnings.push("Tool name starts or ends with a dot, which may cause parsing issues in some contexts"), !TOOL_NAME_REGEX.test(name)) {
    let invalidChars = name.split("").filter((char) => !/[A-Za-z0-9._-]/.test(char)).filter((char, index, arr) => arr.indexOf(char) === index);
    return warnings.push(`Tool name contains invalid characters: ${invalidChars.map((c) => `"${c}"`).join(", ")}`, "Allowed characters are: A-Z, a-z, 0-9, underscore (_), dash (-), and dot (.)"), {
      isValid: !1,
      warnings
    };
  }
  return {
    isValid: !0,
    warnings
  };
}
function issueToolNameWarning(name, warnings) {
  if (warnings.length > 0) {
    console.warn(`Tool name validation warning for "${name}":`);
    for (let warning of warnings)
      console.warn(`  - ${warning}`);
    console.warn("Tool registration will proceed, but this may cause compatibility issues."), console.warn("Consider updating the tool name to conform to the MCP tool naming standard."), console.warn("See SEP: Specify Format for Tool Names (https://github.com/modelcontextprotocol/modelcontextprotocol/issues/986) for more details.");
  }
}
function validateAndWarnToolName(name) {
  let result = validateToolName(name);
  return issueToolNameWarning(name, result.warnings), result.isValid;
}

// node_modules/@modelcontextprotocol/sdk/dist/esm/experimental/tasks/mcp-server.js
var ExperimentalMcpServerTasks = class {
  constructor(_mcpServer) {
    this._mcpServer = _mcpServer;
  }
  registerToolTask(name, config2, handler) {
    let execution = { taskSupport: "required", ...config2.execution };
    if (execution.taskSupport === "forbidden")
      throw new Error(`Cannot register task-based tool '${name}' with taskSupport 'forbidden'. Use registerTool() instead.`);
    return this._mcpServer._createRegisteredTool(name, config2.title, config2.description, config2.inputSchema, config2.outputSchema, config2.annotations, execution, config2._meta, handler);
  }
};

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/mcp.js
function toolInputElementCount(value, max) {
  let count = 0, stack = [value];
  for (; stack.length > 0; ) {
    let node = stack.pop();
    if (!(node === null || typeof node != "object"))
      if (Array.isArray(node))
        for (let child of node) {
          if (++count > max)
            return count;
          child !== null && typeof child == "object" && stack.push(child);
        }
      else
        for (let key in node) {
          if (!Object.prototype.hasOwnProperty.call(node, key))
            continue;
          if (++count > max)
            return count;
          let child = node[key];
          child !== null && typeof child == "object" && stack.push(child);
        }
  }
  return count;
}
function resolveMaxToolInputElements(value) {
  if (!(value === void 0 || value === 1 / 0)) {
    if (typeof value != "number" || Number.isNaN(value) || value < 1)
      throw new RangeError(`maxToolInputElements must be a number of at least 1, or Infinity, got ${String(value)}`);
    return value;
  }
}
var McpServer = class {
  constructor(serverInfo, options) {
    this._registeredResources = {}, this._registeredResourceTemplates = {}, this._registeredTools = {}, this._registeredPrompts = {}, this._toolHandlersInitialized = !1, this._completionHandlerInitialized = !1, this._resourceHandlersInitialized = !1, this._promptHandlersInitialized = !1, this.server = new Server(serverInfo, options), this._maxToolInputElements = resolveMaxToolInputElements(options?.maxToolInputElements), options?.capabilities?.tools && this.setToolRequestHandlers(), options?.capabilities?.resources && this.setResourceRequestHandlers(), options?.capabilities?.prompts && this.setPromptRequestHandlers();
  }
  /**
   * Access experimental features.
   *
   * WARNING: These APIs are experimental and may change without notice.
   *
   * @experimental
   */
  get experimental() {
    return this._experimental || (this._experimental = {
      tasks: new ExperimentalMcpServerTasks(this)
    }), this._experimental;
  }
  /**
   * Attaches to the given transport, starts it, and starts listening for messages.
   *
   * The `server` object assumes ownership of the Transport, replacing any callbacks that have already been set, and expects that it is the only user of the Transport instance going forward.
   */
  async connect(transport) {
    return await this.server.connect(transport);
  }
  /**
   * Closes the connection.
   */
  async close() {
    await this.server.close();
  }
  setToolRequestHandlers() {
    this._toolHandlersInitialized || (this.server.assertCanSetRequestHandler(getMethodValue(ListToolsRequestSchema)), this.server.assertCanSetRequestHandler(getMethodValue(CallToolRequestSchema)), this.server.registerCapabilities({
      tools: {
        listChanged: !0
      }
    }), this.server.setRequestHandler(ListToolsRequestSchema, () => ({
      tools: Object.entries(this._registeredTools).filter(([, tool]) => tool.enabled).map(([name, tool]) => {
        let toolDefinition = {
          name,
          title: tool.title,
          description: tool.description,
          inputSchema: (() => {
            let obj = normalizeObjectSchema(tool.inputSchema);
            return obj ? toJsonSchemaCompat(obj, {
              strictUnions: !0,
              pipeStrategy: "input"
            }) : EMPTY_OBJECT_JSON_SCHEMA;
          })(),
          annotations: tool.annotations,
          execution: tool.execution,
          _meta: tool._meta
        };
        if (tool.outputSchema) {
          let obj = normalizeObjectSchema(tool.outputSchema);
          obj && (toolDefinition.outputSchema = toJsonSchemaCompat(obj, {
            strictUnions: !0,
            pipeStrategy: "output"
          }));
        }
        return toolDefinition;
      })
    })), this.server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
      try {
        let tool = this._registeredTools[request.params.name];
        if (!tool)
          throw new McpError(ErrorCode.InvalidParams, `Tool ${request.params.name} not found`);
        if (!tool.enabled)
          throw new McpError(ErrorCode.InvalidParams, `Tool ${request.params.name} disabled`);
        let isTaskRequest = !!request.params.task, taskSupport = tool.execution?.taskSupport, isTaskHandler = "createTask" in tool.handler;
        if ((taskSupport === "required" || taskSupport === "optional") && !isTaskHandler)
          throw new McpError(ErrorCode.InternalError, `Tool ${request.params.name} has taskSupport '${taskSupport}' but was not registered with registerToolTask`);
        if (taskSupport === "required" && !isTaskRequest)
          throw new McpError(ErrorCode.MethodNotFound, `Tool ${request.params.name} requires task augmentation (taskSupport: 'required')`);
        if (taskSupport === "optional" && !isTaskRequest && isTaskHandler)
          return await this.handleAutomaticTaskPolling(tool, request, extra);
        let args = await this.validateToolInput(tool, request.params.arguments, request.params.name), result = await this.executeToolHandler(tool, args, extra);
        return isTaskRequest || await this.validateToolOutput(tool, result, request.params.name), result;
      } catch (error2) {
        if (error2 instanceof McpError && error2.code === ErrorCode.UrlElicitationRequired)
          throw error2;
        return this.createToolError(error2 instanceof Error ? error2.message : String(error2));
      }
    }), this._toolHandlersInitialized = !0);
  }
  /**
   * Creates a tool error result.
   *
   * @param errorMessage - The error message.
   * @returns The tool error result.
   */
  createToolError(errorMessage) {
    return {
      content: [
        {
          type: "text",
          text: errorMessage
        }
      ],
      isError: !0
    };
  }
  /**
   * Validates tool input arguments against the tool's input schema.
   */
  async validateToolInput(tool, args, toolName) {
    if (this._maxToolInputElements !== void 0 && toolInputElementCount(args, this._maxToolInputElements) > this._maxToolInputElements)
      throw new McpError(ErrorCode.InvalidParams, `Invalid arguments for tool ${toolName}: arguments contain more than the maximum of ${this._maxToolInputElements} elements`);
    if (!tool.inputSchema)
      return;
    let schemaToParse = normalizeObjectSchema(tool.inputSchema) ?? tool.inputSchema, parseResult = await safeParseAsync2(schemaToParse, args ?? {});
    if (!parseResult.success) {
      let error2 = "error" in parseResult ? parseResult.error : "Unknown error", errorMessage = getParseErrorMessage(error2);
      throw new McpError(ErrorCode.InvalidParams, `Input validation error: Invalid arguments for tool ${toolName}: ${errorMessage}`);
    }
    return parseResult.data;
  }
  /**
   * Validates tool output against the tool's output schema.
   */
  async validateToolOutput(tool, result, toolName) {
    if (!tool.outputSchema || !("content" in result) || result.isError)
      return;
    if (!result.structuredContent)
      throw new McpError(ErrorCode.InvalidParams, `Output validation error: Tool ${toolName} has an output schema but no structured content was provided`);
    let outputObj = normalizeObjectSchema(tool.outputSchema), parseResult = await safeParseAsync2(outputObj, result.structuredContent);
    if (!parseResult.success) {
      let error2 = "error" in parseResult ? parseResult.error : "Unknown error", errorMessage = getParseErrorMessage(error2);
      throw new McpError(ErrorCode.InvalidParams, `Output validation error: Invalid structured content for tool ${toolName}: ${errorMessage}`);
    }
  }
  /**
   * Executes a tool handler (either regular or task-based).
   */
  async executeToolHandler(tool, args, extra) {
    let handler = tool.handler;
    if ("createTask" in handler) {
      if (!extra.taskStore)
        throw new Error("No task store provided.");
      let taskExtra = { ...extra, taskStore: extra.taskStore };
      if (tool.inputSchema) {
        let typedHandler = handler;
        return await Promise.resolve(typedHandler.createTask(args, taskExtra));
      } else {
        let typedHandler = handler;
        return await Promise.resolve(typedHandler.createTask(taskExtra));
      }
    }
    if (tool.inputSchema) {
      let typedHandler = handler;
      return await Promise.resolve(typedHandler(args, extra));
    } else {
      let typedHandler = handler;
      return await Promise.resolve(typedHandler(extra));
    }
  }
  /**
   * Handles automatic task polling for tools with taskSupport 'optional'.
   */
  async handleAutomaticTaskPolling(tool, request, extra) {
    if (!extra.taskStore)
      throw new Error("No task store provided for task-capable tool.");
    let args = await this.validateToolInput(tool, request.params.arguments, request.params.name), handler = tool.handler, taskExtra = { ...extra, taskStore: extra.taskStore }, createTaskResult = args ? await Promise.resolve(handler.createTask(args, taskExtra)) : (
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      await Promise.resolve(handler.createTask(taskExtra))
    ), taskId = createTaskResult.task.taskId, task = createTaskResult.task, pollInterval = task.pollInterval ?? 5e3;
    for (; task.status !== "completed" && task.status !== "failed" && task.status !== "cancelled"; ) {
      await new Promise((resolve) => setTimeout(resolve, pollInterval));
      let updatedTask = await extra.taskStore.getTask(taskId);
      if (!updatedTask)
        throw new McpError(ErrorCode.InternalError, `Task ${taskId} not found during polling`);
      task = updatedTask;
    }
    return await extra.taskStore.getTaskResult(taskId);
  }
  setCompletionRequestHandler() {
    this._completionHandlerInitialized || (this.server.assertCanSetRequestHandler(getMethodValue(CompleteRequestSchema)), this.server.registerCapabilities({
      completions: {}
    }), this.server.setRequestHandler(CompleteRequestSchema, async (request) => {
      switch (request.params.ref.type) {
        case "ref/prompt":
          return assertCompleteRequestPrompt(request), this.handlePromptCompletion(request, request.params.ref);
        case "ref/resource":
          return assertCompleteRequestResourceTemplate(request), this.handleResourceCompletion(request, request.params.ref);
        default:
          throw new McpError(ErrorCode.InvalidParams, `Invalid completion reference: ${request.params.ref}`);
      }
    }), this._completionHandlerInitialized = !0);
  }
  async handlePromptCompletion(request, ref2) {
    let prompt = this._registeredPrompts[ref2.name];
    if (!prompt)
      throw new McpError(ErrorCode.InvalidParams, `Prompt ${ref2.name} not found`);
    if (!prompt.enabled)
      throw new McpError(ErrorCode.InvalidParams, `Prompt ${ref2.name} disabled`);
    if (!prompt.argsSchema)
      return EMPTY_COMPLETION_RESULT;
    let field = getObjectShape(prompt.argsSchema)?.[request.params.argument.name];
    if (!isCompletable(field))
      return EMPTY_COMPLETION_RESULT;
    let completer = getCompleter(field);
    if (!completer)
      return EMPTY_COMPLETION_RESULT;
    let suggestions = await completer(request.params.argument.value, request.params.context);
    return createCompletionResult(suggestions);
  }
  async handleResourceCompletion(request, ref2) {
    let template = Object.values(this._registeredResourceTemplates).find((t) => t.resourceTemplate.uriTemplate.toString() === ref2.uri);
    if (!template) {
      if (this._registeredResources[ref2.uri])
        return EMPTY_COMPLETION_RESULT;
      throw new McpError(ErrorCode.InvalidParams, `Resource template ${request.params.ref.uri} not found`);
    }
    let completer = template.resourceTemplate.completeCallback(request.params.argument.name);
    if (!completer)
      return EMPTY_COMPLETION_RESULT;
    let suggestions = await completer(request.params.argument.value, request.params.context);
    return createCompletionResult(suggestions);
  }
  setResourceRequestHandlers() {
    this._resourceHandlersInitialized || (this.server.assertCanSetRequestHandler(getMethodValue(ListResourcesRequestSchema)), this.server.assertCanSetRequestHandler(getMethodValue(ListResourceTemplatesRequestSchema)), this.server.assertCanSetRequestHandler(getMethodValue(ReadResourceRequestSchema)), this.server.registerCapabilities({
      resources: {
        listChanged: !0
      }
    }), this.server.setRequestHandler(ListResourcesRequestSchema, async (request, extra) => {
      let resources = Object.entries(this._registeredResources).filter(([_, resource]) => resource.enabled).map(([uri, resource]) => ({
        uri,
        name: resource.name,
        ...resource.metadata
      })), templateResources = [];
      for (let template of Object.values(this._registeredResourceTemplates)) {
        if (!template.resourceTemplate.listCallback)
          continue;
        let result = await template.resourceTemplate.listCallback(extra);
        for (let resource of result.resources)
          templateResources.push({
            ...template.metadata,
            // the defined resource metadata should override the template metadata if present
            ...resource
          });
      }
      return { resources: [...resources, ...templateResources] };
    }), this.server.setRequestHandler(ListResourceTemplatesRequestSchema, async () => ({ resourceTemplates: Object.entries(this._registeredResourceTemplates).map(([name, template]) => ({
      name,
      uriTemplate: template.resourceTemplate.uriTemplate.toString(),
      ...template.metadata
    })) })), this.server.setRequestHandler(ReadResourceRequestSchema, async (request, extra) => {
      let uri = new URL(request.params.uri), resource = this._registeredResources[uri.toString()];
      if (resource) {
        if (!resource.enabled)
          throw new McpError(ErrorCode.InvalidParams, `Resource ${uri} disabled`);
        return resource.readCallback(uri, extra);
      }
      for (let template of Object.values(this._registeredResourceTemplates)) {
        let variables = template.resourceTemplate.uriTemplate.match(uri.toString());
        if (variables)
          return template.readCallback(uri, variables, extra);
      }
      throw new McpError(ErrorCode.InvalidParams, `Resource ${uri} not found`);
    }), this._resourceHandlersInitialized = !0);
  }
  setPromptRequestHandlers() {
    this._promptHandlersInitialized || (this.server.assertCanSetRequestHandler(getMethodValue(ListPromptsRequestSchema)), this.server.assertCanSetRequestHandler(getMethodValue(GetPromptRequestSchema)), this.server.registerCapabilities({
      prompts: {
        listChanged: !0
      }
    }), this.server.setRequestHandler(ListPromptsRequestSchema, () => ({
      prompts: Object.entries(this._registeredPrompts).filter(([, prompt]) => prompt.enabled).map(([name, prompt]) => ({
        name,
        title: prompt.title,
        description: prompt.description,
        arguments: prompt.argsSchema ? promptArgumentsFromSchema(prompt.argsSchema) : void 0
      }))
    })), this.server.setRequestHandler(GetPromptRequestSchema, async (request, extra) => {
      let prompt = this._registeredPrompts[request.params.name];
      if (!prompt)
        throw new McpError(ErrorCode.InvalidParams, `Prompt ${request.params.name} not found`);
      if (!prompt.enabled)
        throw new McpError(ErrorCode.InvalidParams, `Prompt ${request.params.name} disabled`);
      if (prompt.argsSchema) {
        let argsObj = normalizeObjectSchema(prompt.argsSchema), parseResult = await safeParseAsync2(argsObj, request.params.arguments ?? {});
        if (!parseResult.success) {
          let error2 = "error" in parseResult ? parseResult.error : "Unknown error", errorMessage = getParseErrorMessage(error2);
          throw new McpError(ErrorCode.InvalidParams, `Invalid arguments for prompt ${request.params.name}: ${errorMessage}`);
        }
        let args = parseResult.data, cb = prompt.callback;
        return await Promise.resolve(cb(args, extra));
      } else {
        let cb = prompt.callback;
        return await Promise.resolve(cb(extra));
      }
    }), this._promptHandlersInitialized = !0);
  }
  resource(name, uriOrTemplate, ...rest) {
    let metadata;
    typeof rest[0] == "object" && (metadata = rest.shift());
    let readCallback = rest[0];
    if (typeof uriOrTemplate == "string") {
      if (this._registeredResources[uriOrTemplate])
        throw new Error(`Resource ${uriOrTemplate} is already registered`);
      let registeredResource = this._createRegisteredResource(name, void 0, uriOrTemplate, metadata, readCallback);
      return this.setResourceRequestHandlers(), this.sendResourceListChanged(), registeredResource;
    } else {
      if (this._registeredResourceTemplates[name])
        throw new Error(`Resource template ${name} is already registered`);
      let registeredResourceTemplate = this._createRegisteredResourceTemplate(name, void 0, uriOrTemplate, metadata, readCallback);
      return this.setResourceRequestHandlers(), this.sendResourceListChanged(), registeredResourceTemplate;
    }
  }
  registerResource(name, uriOrTemplate, config2, readCallback) {
    if (typeof uriOrTemplate == "string") {
      if (this._registeredResources[uriOrTemplate])
        throw new Error(`Resource ${uriOrTemplate} is already registered`);
      let registeredResource = this._createRegisteredResource(name, config2.title, uriOrTemplate, config2, readCallback);
      return this.setResourceRequestHandlers(), this.sendResourceListChanged(), registeredResource;
    } else {
      if (this._registeredResourceTemplates[name])
        throw new Error(`Resource template ${name} is already registered`);
      let registeredResourceTemplate = this._createRegisteredResourceTemplate(name, config2.title, uriOrTemplate, config2, readCallback);
      return this.setResourceRequestHandlers(), this.sendResourceListChanged(), registeredResourceTemplate;
    }
  }
  _createRegisteredResource(name, title, uri, metadata, readCallback) {
    let registeredResource = {
      name,
      title,
      metadata,
      readCallback,
      enabled: !0,
      disable: () => registeredResource.update({ enabled: !1 }),
      enable: () => registeredResource.update({ enabled: !0 }),
      remove: () => registeredResource.update({ uri: null }),
      update: (updates) => {
        typeof updates.uri < "u" && updates.uri !== uri && (delete this._registeredResources[uri], updates.uri && (this._registeredResources[updates.uri] = registeredResource)), typeof updates.name < "u" && (registeredResource.name = updates.name), typeof updates.title < "u" && (registeredResource.title = updates.title), typeof updates.metadata < "u" && (registeredResource.metadata = updates.metadata), typeof updates.callback < "u" && (registeredResource.readCallback = updates.callback), typeof updates.enabled < "u" && (registeredResource.enabled = updates.enabled), this.sendResourceListChanged();
      }
    };
    return this._registeredResources[uri] = registeredResource, registeredResource;
  }
  _createRegisteredResourceTemplate(name, title, template, metadata, readCallback) {
    let registeredResourceTemplate = {
      resourceTemplate: template,
      title,
      metadata,
      readCallback,
      enabled: !0,
      disable: () => registeredResourceTemplate.update({ enabled: !1 }),
      enable: () => registeredResourceTemplate.update({ enabled: !0 }),
      remove: () => registeredResourceTemplate.update({ name: null }),
      update: (updates) => {
        typeof updates.name < "u" && updates.name !== name && (delete this._registeredResourceTemplates[name], updates.name && (this._registeredResourceTemplates[updates.name] = registeredResourceTemplate)), typeof updates.title < "u" && (registeredResourceTemplate.title = updates.title), typeof updates.template < "u" && (registeredResourceTemplate.resourceTemplate = updates.template), typeof updates.metadata < "u" && (registeredResourceTemplate.metadata = updates.metadata), typeof updates.callback < "u" && (registeredResourceTemplate.readCallback = updates.callback), typeof updates.enabled < "u" && (registeredResourceTemplate.enabled = updates.enabled), this.sendResourceListChanged();
      }
    };
    this._registeredResourceTemplates[name] = registeredResourceTemplate;
    let variableNames = template.uriTemplate.variableNames;
    return Array.isArray(variableNames) && variableNames.some((v) => !!template.completeCallback(v)) && this.setCompletionRequestHandler(), registeredResourceTemplate;
  }
  _createRegisteredPrompt(name, title, description, argsSchema, callback) {
    let registeredPrompt = {
      title,
      description,
      argsSchema: argsSchema === void 0 ? void 0 : objectFromShape(argsSchema),
      callback,
      enabled: !0,
      disable: () => registeredPrompt.update({ enabled: !1 }),
      enable: () => registeredPrompt.update({ enabled: !0 }),
      remove: () => registeredPrompt.update({ name: null }),
      update: (updates) => {
        typeof updates.name < "u" && updates.name !== name && (delete this._registeredPrompts[name], updates.name && (this._registeredPrompts[updates.name] = registeredPrompt)), typeof updates.title < "u" && (registeredPrompt.title = updates.title), typeof updates.description < "u" && (registeredPrompt.description = updates.description), typeof updates.argsSchema < "u" && (registeredPrompt.argsSchema = objectFromShape(updates.argsSchema)), typeof updates.callback < "u" && (registeredPrompt.callback = updates.callback), typeof updates.enabled < "u" && (registeredPrompt.enabled = updates.enabled), this.sendPromptListChanged();
      }
    };
    return this._registeredPrompts[name] = registeredPrompt, argsSchema && Object.values(argsSchema).some((field) => {
      let inner = field instanceof ZodOptional ? field._def?.innerType : field;
      return isCompletable(inner);
    }) && this.setCompletionRequestHandler(), registeredPrompt;
  }
  _createRegisteredTool(name, title, description, inputSchema, outputSchema, annotations, execution, _meta, handler) {
    validateAndWarnToolName(name);
    let registeredTool = {
      title,
      description,
      inputSchema: getZodSchemaObject(inputSchema),
      outputSchema: getZodSchemaObject(outputSchema),
      annotations,
      execution,
      _meta,
      handler,
      enabled: !0,
      disable: () => registeredTool.update({ enabled: !1 }),
      enable: () => registeredTool.update({ enabled: !0 }),
      remove: () => registeredTool.update({ name: null }),
      update: (updates) => {
        typeof updates.name < "u" && updates.name !== name && (typeof updates.name == "string" && validateAndWarnToolName(updates.name), delete this._registeredTools[name], updates.name && (this._registeredTools[updates.name] = registeredTool)), typeof updates.title < "u" && (registeredTool.title = updates.title), typeof updates.description < "u" && (registeredTool.description = updates.description), typeof updates.paramsSchema < "u" && (registeredTool.inputSchema = objectFromShape(updates.paramsSchema)), typeof updates.outputSchema < "u" && (registeredTool.outputSchema = objectFromShape(updates.outputSchema)), typeof updates.callback < "u" && (registeredTool.handler = updates.callback), typeof updates.annotations < "u" && (registeredTool.annotations = updates.annotations), typeof updates._meta < "u" && (registeredTool._meta = updates._meta), typeof updates.enabled < "u" && (registeredTool.enabled = updates.enabled), this.sendToolListChanged();
      }
    };
    return this._registeredTools[name] = registeredTool, this.setToolRequestHandlers(), this.sendToolListChanged(), registeredTool;
  }
  /**
   * tool() implementation. Parses arguments passed to overrides defined above.
   */
  tool(name, ...rest) {
    if (this._registeredTools[name])
      throw new Error(`Tool ${name} is already registered`);
    let description, inputSchema, outputSchema, annotations;
    if (typeof rest[0] == "string" && (description = rest.shift()), rest.length > 1) {
      let firstArg = rest[0];
      if (isZodRawShapeCompat(firstArg))
        inputSchema = rest.shift(), rest.length > 1 && typeof rest[0] == "object" && rest[0] !== null && !isZodRawShapeCompat(rest[0]) && (annotations = rest.shift());
      else if (typeof firstArg == "object" && firstArg !== null) {
        if (Object.values(firstArg).some((v) => typeof v == "object" && v !== null))
          throw new Error(`Tool ${name} expected a Zod schema or ToolAnnotations, but received an unrecognized object`);
        annotations = rest.shift();
      }
    }
    let callback = rest[0];
    return this._createRegisteredTool(name, void 0, description, inputSchema, outputSchema, annotations, { taskSupport: "forbidden" }, void 0, callback);
  }
  /**
   * Registers a tool with a config object and callback.
   */
  registerTool(name, config2, cb) {
    if (this._registeredTools[name])
      throw new Error(`Tool ${name} is already registered`);
    let { title, description, inputSchema, outputSchema, annotations, _meta } = config2;
    return this._createRegisteredTool(name, title, description, inputSchema, outputSchema, annotations, { taskSupport: "forbidden" }, _meta, cb);
  }
  prompt(name, ...rest) {
    if (this._registeredPrompts[name])
      throw new Error(`Prompt ${name} is already registered`);
    let description;
    typeof rest[0] == "string" && (description = rest.shift());
    let argsSchema;
    rest.length > 1 && (argsSchema = rest.shift());
    let cb = rest[0], registeredPrompt = this._createRegisteredPrompt(name, void 0, description, argsSchema, cb);
    return this.setPromptRequestHandlers(), this.sendPromptListChanged(), registeredPrompt;
  }
  /**
   * Registers a prompt with a config object and callback.
   */
  registerPrompt(name, config2, cb) {
    if (this._registeredPrompts[name])
      throw new Error(`Prompt ${name} is already registered`);
    let { title, description, argsSchema } = config2, registeredPrompt = this._createRegisteredPrompt(name, title, description, argsSchema, cb);
    return this.setPromptRequestHandlers(), this.sendPromptListChanged(), registeredPrompt;
  }
  /**
   * Checks if the server is connected to a transport.
   * @returns True if the server is connected
   */
  isConnected() {
    return this.server.transport !== void 0;
  }
  /**
   * Sends a logging message to the client, if connected.
   * Note: You only need to send the parameters object, not the entire JSON RPC message
   * @see LoggingMessageNotification
   * @param params
   * @param sessionId optional for stateless and backward compatibility
   */
  async sendLoggingMessage(params, sessionId) {
    return this.server.sendLoggingMessage(params, sessionId);
  }
  /**
   * Sends a resource list changed event to the client, if connected.
   */
  sendResourceListChanged() {
    this.isConnected() && this.server.sendResourceListChanged();
  }
  /**
   * Sends a tool list changed event to the client, if connected.
   */
  sendToolListChanged() {
    this.isConnected() && this.server.sendToolListChanged();
  }
  /**
   * Sends a prompt list changed event to the client, if connected.
   */
  sendPromptListChanged() {
    this.isConnected() && this.server.sendPromptListChanged();
  }
};
var EMPTY_OBJECT_JSON_SCHEMA = {
  type: "object",
  properties: {}
};
function isZodTypeLike(value) {
  return value !== null && typeof value == "object" && "parse" in value && typeof value.parse == "function" && "safeParse" in value && typeof value.safeParse == "function";
}
function isZodSchemaInstance(obj) {
  return "_def" in obj || "_zod" in obj || isZodTypeLike(obj);
}
function isZodRawShapeCompat(obj) {
  return typeof obj != "object" || obj === null || isZodSchemaInstance(obj) ? !1 : Object.keys(obj).length === 0 ? !0 : Object.values(obj).some(isZodTypeLike);
}
function getZodSchemaObject(schema) {
  if (schema) {
    if (isZodRawShapeCompat(schema))
      return objectFromShape(schema);
    if (!isZodSchemaInstance(schema))
      throw new Error("inputSchema must be a Zod schema or raw shape, received an unrecognized object");
    return schema;
  }
}
function promptArgumentsFromSchema(schema) {
  let shape = getObjectShape(schema);
  return shape ? Object.entries(shape).map(([name, field]) => {
    let description = getSchemaDescription(field), isOptional = isSchemaOptional(field);
    return {
      name,
      description,
      required: !isOptional
    };
  }) : [];
}
function getMethodValue(schema) {
  let methodSchema = getObjectShape(schema)?.method;
  if (!methodSchema)
    throw new Error("Schema is missing a method literal");
  let value = getLiteralValue(methodSchema);
  if (typeof value == "string")
    return value;
  throw new Error("Schema method literal must be a string");
}
function createCompletionResult(suggestions) {
  return {
    completion: {
      values: suggestions.slice(0, 100),
      total: suggestions.length,
      hasMore: suggestions.length > 100
    }
  };
}
var EMPTY_COMPLETION_RESULT = {
  completion: {
    values: [],
    hasMore: !1
  }
};

// node_modules/@hono/node-server/dist/constants-BLSFu_RU.mjs
var X_ALREADY_SENT = "x-hono-already-sent";

// node_modules/@hono/node-server/dist/index.mjs
import { STATUS_CODES, ServerResponse, createServer } from "node:http";
import { Http2ServerRequest, constants } from "node:http2";
import { Readable } from "node:stream";

// node_modules/hono/dist/helper/websocket/index.js
var defineWebSocketHelper = (handler) => ((...args) => {
  if (typeof args[0] == "function") {
    let [createEvents, options] = args;
    return async function(c, next) {
      let result = await handler(c, await createEvents(c), options);
      if (result) return result;
      await next();
    };
  } else {
    let [c, events, options] = args;
    return (async () => {
      let upgraded = await handler(c, events, options);
      if (!upgraded) throw new Error("Failed to upgrade WebSocket");
      return upgraded;
    })();
  }
});

// node_modules/@hono/node-server/dist/index.mjs
var RequestError = class extends Error {
  constructor(message, options) {
    super(message, options), this.name = "RequestError";
  }
}, nonJoinedHeaders = /* @__PURE__ */ new Set([
  "age",
  "authorization",
  "content-length",
  "content-type",
  "etag",
  "expires",
  "from",
  "host",
  "if-modified-since",
  "if-unmodified-since",
  "last-modified",
  "location",
  "max-forwards",
  "proxy-authorization",
  "referer",
  "retry-after",
  "server",
  "user-agent"
]), validHeaderName = /^[!#$%&'*+\-.^_`|~\dA-Za-z]+$/, isHttpWhitespace = (code) => code === 9 || code === 10 || code === 13 || code === 32, normalizeHeaderValue = (value) => {
  if (!isHttpWhitespace(value.charCodeAt(0)) && !isHttpWhitespace(value.charCodeAt(value.length - 1))) return value;
  let start = 0, end = value.length;
  for (; start < end && isHttpWhitespace(value.charCodeAt(start)); ) start++;
  for (; end > start && isHttpWhitespace(value.charCodeAt(end - 1)); ) end--;
  return value.slice(start, end);
}, forbiddenHeaderValue = /[\0\r\n]/, GlobalHeaders = globalThis.Headers, materializeHeaders = (rawHeaders, HeadersCtor = GlobalHeaders) => {
  let headers = new HeadersCtor();
  for (let i = 0; i < rawHeaders.length; i += 2) {
    let name = rawHeaders[i];
    name.startsWith(":") || headers.append(name, rawHeaders[i + 1]);
  }
  return headers;
}, RequestHeaders = class {
  #incoming;
  #rawHeaders;
  #headers;
  #invalidValue;
  constructor(incoming) {
    this.#incoming = incoming, incoming instanceof Http2ServerRequest && (this.#rawHeaders = incoming.rawHeaders.slice());
  }
  get #lazyRawHeaders() {
    return this.#rawHeaders ??= this.#incoming.rawHeaders.slice();
  }
  get #native() {
    return this.#headers || (this.#headers = materializeHeaders(this.#lazyRawHeaders), this.#rawHeaders = void 0), this.#headers;
  }
  #normalizedName(name) {
    if (typeof name == "string") {
      if (!validHeaderName.test(name)) throw new TypeError(`Invalid header name: ${name}`);
      return name.toLowerCase();
    }
  }
  #lookupHttp1(lowerName) {
    let headers = this.#incoming instanceof Http2ServerRequest ? void 0 : this.#incoming.headers;
    if (!headers || nonJoinedHeaders.has(lowerName) || lowerName === "set-cookie" || lowerName === "__proto__") return;
    if (!Object.hasOwn(headers, lowerName)) return null;
    let rawValue = headers[lowerName];
    if (typeof rawValue == "string") {
      let value = normalizeHeaderValue(rawValue);
      return forbiddenHeaderValue.test(value) ? void 0 : value;
    }
  }
  #lookup(rawHeaders, lowerName) {
    let separator = lowerName === "cookie" ? "; " : ", ", value = null;
    for (let i = 0; i < rawHeaders.length; i += 2) {
      let rawName = rawHeaders[i];
      if (rawName.length === lowerName.length && rawName.toLowerCase() === lowerName) {
        let rawValue = normalizeHeaderValue(rawHeaders[i + 1]);
        if (forbiddenHeaderValue.test(rawValue)) {
          this.#invalidValue = !0;
          return;
        }
        value = value === null ? rawValue : value + separator + rawValue;
      }
    }
    return value;
  }
  append(name, value) {
    this.#native.append(name, value);
  }
  delete(name) {
    this.#native.delete(name);
  }
  get(name) {
    let lowerName = this.#normalizedName(name);
    if (lowerName && !this.#headers && !this.#invalidValue) {
      let http1Value = this.#lookupHttp1(lowerName);
      if (http1Value !== void 0) return http1Value;
      let value = this.#lookup(this.#lazyRawHeaders, lowerName);
      if (value !== void 0) return value;
    }
    return this.#native.get(name);
  }
  has(name) {
    let lowerName = this.#normalizedName(name);
    if (lowerName && !this.#headers && !this.#invalidValue) {
      let http1Value = this.#lookupHttp1(lowerName);
      if (http1Value !== void 0) return http1Value !== null;
      let value = this.#lookup(this.#lazyRawHeaders, lowerName);
      if (value !== void 0) return value !== null;
    }
    return this.#native.has(name);
  }
  set(name, value) {
    this.#native.set(name, value);
  }
  getSetCookie() {
    return this.#native.getSetCookie();
  }
  keys() {
    return this.#native.keys();
  }
  values() {
    return this.#native.values();
  }
  entries() {
    return this.#native.entries();
  }
  forEach(callback, thisArg) {
    this.#native.forEach((value, key) => {
      callback.call(thisArg, value, key, this);
    });
  }
  [Symbol.iterator]() {
    return this.entries();
  }
};
Object.defineProperty(RequestHeaders.prototype, Symbol.for("nodejs.util.inspect.custom"), { value: function(depth, options, inspectFn) {
  return `Headers (lightweight) ${inspectFn(Object.fromEntries(this), {
    ...options,
    depth: depth == null ? null : depth - 1
  })}`;
} });
Object.setPrototypeOf(RequestHeaders.prototype, GlobalHeaders.prototype);
var newHeadersFromIncoming = (incoming) => globalThis.Headers === GlobalHeaders ? new RequestHeaders(incoming) : materializeHeaders(incoming.rawHeaders, globalThis.Headers), reValidRequestUrl = /^\/[!#$&-;=?-\[\]_a-z~]*$/, reDotSegment = /\/\.\.?(?:[/?#]|$)/, reValidHost = /^[a-z0-9._-]+(?::(?:[1-5]\d{3,4}|[6-9]\d{3}))?$/, validateRequestTarget = (target) => {
  let backslash = target.indexOf("\\");
  if (backslash !== -1) {
    let query = target.indexOf("?");
    if (query === -1 || backslash < query) throw new RequestError("Backslash in request path");
  }
}, buildUrl = (scheme, host, incomingUrl) => {
  let url = `${scheme}://${host}${incomingUrl}`;
  if (reValidHost.test(host)) {
    if (incomingUrl.length === 0) return url + "/";
    if (incomingUrl.charCodeAt(0) !== 47) throw new RequestError("Invalid URL");
    return !reValidRequestUrl.test(incomingUrl) || reDotSegment.test(incomingUrl) ? (validateRequestTarget(incomingUrl), new URL(url).href) : url;
  } else {
    validateRequestTarget(incomingUrl);
    let urlObj = new URL(url);
    if (urlObj.hostname.length !== host.length && urlObj.hostname !== (host.includes(":") ? host.replace(/:\d+$/, "") : host).toLowerCase()) throw new RequestError("Invalid host header");
    return urlObj.href;
  }
}, toRequestError = (e) => e instanceof RequestError ? e : new RequestError(e.message, { cause: e }), GlobalRequest = global.Request, Request$1 = class extends GlobalRequest {
  constructor(input, options) {
    if (typeof input == "object" && getRequestCache in input) {
      let hasReplacementBody = options !== void 0 && "body" in options && options.body != null;
      if (input[bodyConsumedDirectlyKey] && !hasReplacementBody) throw new TypeError("Cannot construct a Request with a Request object that has already been used.");
      input = input[getRequestCache]();
    }
    typeof options?.body?.getReader < "u" && (options.duplex ??= "half"), super(input, options);
  }
}, wrapBodyStream = Symbol("wrapBodyStream"), byteExactEncodings = /* @__PURE__ */ new Set([
  "latin1",
  "binary",
  "hex",
  "base64",
  "base64url"
]), isByteExactEncoding = (encoding) => encoding === null || byteExactEncodings.has(encoding), bodyBufferedBeforeDisconnectKey = Symbol("bodyBufferedBeforeDisconnect"), bodyBufferedLengthBeforeDisconnectKey = Symbol("bodyBufferedLengthBeforeDisconnect"), toBufferChunk = (chunk, encoding) => Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding ?? "utf8"), isRecoverableDisconnectedIncoming = (incoming) => !(incoming instanceof Http2ServerRequest) && !!incoming.complete && !!incoming.readableAborted && typeof incoming.read == "function" && isByteExactEncoding(incoming.readableEncoding), recordBodyBufferedBeforeDisconnect = (incoming) => {
  if (incoming.readableDidRead || !isRecoverableDisconnectedIncoming(incoming)) return;
  let incomingWithRecovery = incoming;
  incomingWithRecovery[bodyBufferedLengthBeforeDisconnectKey] ??= incoming.readableLength;
}, readBodyBufferedBeforeDisconnect = (incoming, chunks) => {
  if (incoming.readableDidRead && !chunks || !isRecoverableDisconnectedIncoming(incoming)) return;
  let incomingWithRecovery = incoming;
  if (incomingWithRecovery[bodyBufferedBeforeDisconnectKey] !== void 0) return incomingWithRecovery[bodyBufferedBeforeDisconnectKey];
  let result, errored = incoming.errored;
  if (errored && errored.code !== "ECONNRESET") result = errored;
  else if (incomingWithRecovery[bodyBufferedLengthBeforeDisconnectKey] !== void 0 && incoming.readableLength !== incomingWithRecovery[bodyBufferedLengthBeforeDisconnectKey]) result = newBodyUnusableError();
  else {
    let bodyChunks = chunks ?? [], chunk = incoming.read();
    chunk !== null && bodyChunks.push(toBufferChunk(chunk, incoming.readableEncoding));
    let buffer = bodyChunks.length === 1 ? bodyChunks[0] : Buffer.concat(bodyChunks);
    result = buffer;
    let contentLength = incoming.headers["content-length"];
    if (typeof contentLength == "string" && /^\d+$/.test(contentLength)) {
      let expectedLength = Number(contentLength);
      Number.isSafeInteger(expectedLength) && buffer.length !== expectedLength && (result = newBodyUnusableError());
    }
  }
  return incomingWithRecovery[bodyBufferedBeforeDisconnectKey] = result, result;
}, enqueueBufferedBody = (controller, buffered) => {
  if (buffered instanceof Error) {
    controller.error(buffered);
    return;
  }
  buffered.length > 0 && controller.enqueue(buffered), controller.close();
}, newRequestFromIncoming = (method, url, headers, incoming, abortController) => {
  let init = {
    method,
    headers,
    signal: abortController.signal
  };
  if (method === "TRACE") {
    init.method = "GET";
    let req = new Request$1(url, init);
    return Object.defineProperty(req, "method", { get() {
      return "TRACE";
    } }), req;
  }
  if (!(method === "GET" || method === "HEAD")) if ("rawBody" in incoming && incoming.rawBody instanceof Buffer) init.body = new ReadableStream({ start(controller) {
    controller.enqueue(incoming.rawBody), controller.close();
  } });
  else if (incoming[wrapBodyStream]) {
    let reader;
    init.body = new ReadableStream({ async pull(controller) {
      try {
        if (!reader) {
          let buffered = readBodyBufferedBeforeDisconnect(incoming);
          if (buffered !== void 0) {
            enqueueBufferedBody(controller, buffered);
            return;
          }
        }
        reader ||= Readable.toWeb(incoming).getReader();
        let { done, value } = await reader.read();
        done ? controller.close() : controller.enqueue(value);
      } catch (error2) {
        controller.error(error2);
      }
    } });
  } else {
    let buffered = readBodyBufferedBeforeDisconnect(incoming);
    buffered !== void 0 ? init.body = new ReadableStream({ start(controller) {
      enqueueBufferedBody(controller, buffered);
    } }) : init.body = Readable.toWeb(incoming);
  }
  return new Request$1(url, init);
}, getRequestCache = Symbol("getRequestCache"), requestCache = Symbol("requestCache"), incomingKey = Symbol("incomingKey"), urlKey = Symbol("urlKey"), methodKey = Symbol("methodKey"), headersKey = Symbol("headersKey"), abortControllerKey = Symbol("abortControllerKey"), getAbortController = Symbol("getAbortController"), abortRequest = Symbol("abortRequest"), bodyBufferKey = Symbol("bodyBuffer"), bodyReadPromiseKey = Symbol("bodyReadPromise"), bodyConsumedDirectlyKey = Symbol("bodyConsumedDirectly"), bodyLockReaderKey = Symbol("bodyLockReader"), abortReasonKey = Symbol("abortReason"), newBodyUnusableError = () => /* @__PURE__ */ new TypeError("Body is unusable"), rejectBodyUnusable = () => Promise.reject(newBodyUnusableError()), textDecoder = new TextDecoder(), consumeBodyDirectOnce = (request) => {
  if (request[bodyConsumedDirectlyKey]) return rejectBodyUnusable();
  request[bodyConsumedDirectlyKey] = !0;
}, toArrayBuffer = (buf) => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), contentType = (request) => (request[headersKey] ||= newHeadersFromIncoming(request[incomingKey])).get("content-type") || "", methodTokenRegExp = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/, normalizeIncomingMethod = (method) => {
  if (typeof method != "string" || method.length === 0) return "GET";
  switch (method) {
    case "DELETE":
    case "GET":
    case "HEAD":
    case "OPTIONS":
    case "PATCH":
    case "POST":
    case "PUT":
    case "QUERY":
      return method;
  }
  let upper = method.toUpperCase();
  switch (upper) {
    case "DELETE":
    case "GET":
    case "HEAD":
    case "OPTIONS":
    case "POST":
    case "PUT":
      return upper;
    default:
      return method;
  }
}, validateDirectReadMethod = (method) => {
  if (!methodTokenRegExp.test(method)) return /* @__PURE__ */ new TypeError(`'${method}' is not a valid HTTP method.`);
  let normalized = method.toUpperCase();
  if (normalized === "CONNECT" || normalized === "TRACK" || normalized === "TRACE" && method !== "TRACE") return /* @__PURE__ */ new TypeError(`'${method}' HTTP method is unsupported.`);
}, readBodyWithFastPath = (request, method, fromBuffer) => {
  if (request[bodyConsumedDirectlyKey]) return rejectBodyUnusable();
  let methodName = request.method;
  if (methodName === "GET" || methodName === "HEAD") return request[getRequestCache]()[method]();
  let methodValidationError = validateDirectReadMethod(methodName);
  if (methodValidationError) return Promise.reject(methodValidationError);
  if (request[requestCache] && methodName !== "TRACE")
    return request[requestCache][method]();
  let alreadyUsedError = consumeBodyDirectOnce(request);
  if (alreadyUsedError) return alreadyUsedError;
  let raw = readRawBodyIfAvailable(request);
  if (raw) {
    let result = Promise.resolve(fromBuffer(raw, request));
    return request[bodyBufferKey] = void 0, result;
  }
  return readBodyDirect(request).then((buf) => {
    let result = fromBuffer(buf, request);
    return request[bodyBufferKey] = void 0, result;
  });
}, readRawBodyIfAvailable = (request) => {
  let incoming = request[incomingKey];
  if ("rawBody" in incoming && incoming.rawBody instanceof Buffer) return incoming.rawBody;
}, normalizeAbortError = (request, incoming) => {
  if (incoming.errored) return incoming.errored;
  let reason = request[abortReasonKey];
  return reason !== void 0 ? reason instanceof Error ? reason : new Error(String(reason)) : /* @__PURE__ */ new Error("Client connection prematurely closed.");
}, readBodyDirect = (request) => {
  if (request[bodyBufferKey]) return Promise.resolve(request[bodyBufferKey]);
  if (request[bodyReadPromiseKey]) return request[bodyReadPromiseKey];
  let incoming = request[incomingKey];
  if (incoming.readableDidRead) return rejectBodyUnusable();
  let buffered = readBodyBufferedBeforeDisconnect(incoming);
  if (buffered !== void 0)
    return buffered instanceof Error ? Promise.reject(buffered) : (request[bodyBufferKey] = buffered, Promise.resolve(buffered));
  let promise = new Promise((resolve, reject) => {
    let chunks = [], settled = !1, finish = (callback) => {
      settled || (settled = !0, cleanup(), callback());
    }, recoverCompleteBodyAfterDisconnect = (error2) => {
      let streamError = incoming.errored ?? error2;
      return !isRecoverableDisconnectedIncoming(incoming) || streamError && streamError.code !== "ECONNRESET" ? !1 : (finish(() => {
        let recovered = readBodyBufferedBeforeDisconnect(incoming, chunks);
        recovered instanceof Error ? reject(recovered) : recovered === void 0 ? reject(error2 ?? normalizeAbortError(request, incoming)) : (request[bodyBufferKey] = recovered, resolve(recovered));
      }), !0);
    }, onData = (chunk) => {
      chunks.push(toBufferChunk(chunk, incoming.readableEncoding));
    }, onEnd = () => {
      finish(() => {
        let buffer = chunks.length === 1 ? chunks[0] : Buffer.concat(chunks);
        request[bodyBufferKey] = buffer, resolve(buffer);
      });
    }, onError = (error2) => {
      recoverCompleteBodyAfterDisconnect(error2) || finish(() => {
        reject(error2);
      });
    }, onClose = () => {
      if (incoming.readableEnded) {
        onEnd();
        return;
      }
      recoverCompleteBodyAfterDisconnect() || finish(() => {
        reject(normalizeAbortError(request, incoming));
      });
    }, cleanup = () => {
      incoming.off("data", onData), incoming.off("end", onEnd), incoming.off("error", onError), incoming.off("close", onClose), request[bodyReadPromiseKey] = void 0;
    };
    incoming.on("data", onData), incoming.on("end", onEnd), incoming.on("error", onError), incoming.on("close", onClose), queueMicrotask(() => {
      settled || (incoming.readableEnded ? onEnd() : incoming.errored ? onError(incoming.errored) : incoming.destroyed && onClose());
    });
  });
  return request[bodyReadPromiseKey] = promise, promise;
}, requestPrototype = {
  get method() {
    return this[methodKey];
  },
  get url() {
    return this[urlKey];
  },
  get headers() {
    return this[headersKey] ||= newHeadersFromIncoming(this[incomingKey]);
  },
  [abortRequest](reason) {
    this[abortReasonKey] === void 0 && (this[abortReasonKey] = reason);
    let abortController = this[abortControllerKey];
    abortController && !abortController.signal.aborted && abortController.abort(reason);
  },
  [getAbortController]() {
    return this[abortControllerKey] ||= new AbortController(), this[abortReasonKey] !== void 0 && !this[abortControllerKey].signal.aborted && this[abortControllerKey].abort(this[abortReasonKey]), this[abortControllerKey];
  },
  [getRequestCache]() {
    let abortController = this[getAbortController]();
    if (this[requestCache]) return this[requestCache];
    let method = this.method;
    if (this[bodyConsumedDirectlyKey] && !(method === "GET" || method === "HEAD")) {
      this[bodyBufferKey] = void 0;
      let init = {
        method: method === "TRACE" ? "GET" : method,
        headers: this.headers,
        signal: abortController.signal
      };
      method !== "TRACE" && (init.body = new ReadableStream({ start(c) {
        c.close();
      } }), init.duplex = "half");
      let req = new Request$1(this[urlKey], init);
      return method === "TRACE" && Object.defineProperty(req, "method", { get() {
        return "TRACE";
      } }), this[requestCache] = req;
    }
    return this[requestCache] = newRequestFromIncoming(this.method, this[urlKey], this.headers, this[incomingKey], abortController);
  },
  get body() {
    if (!this[bodyConsumedDirectlyKey]) return this[getRequestCache]().body;
    let request = this[getRequestCache]();
    return !this[bodyLockReaderKey] && request.body && (this[bodyLockReaderKey] = request.body.getReader()), request.body;
  },
  get bodyUsed() {
    return this[bodyConsumedDirectlyKey] ? !0 : this[requestCache] ? this[requestCache].bodyUsed : !1;
  }
};
Object.defineProperty(requestPrototype, "signal", { get() {
  return this[getAbortController]().signal;
} });
[
  "cache",
  "credentials",
  "destination",
  "integrity",
  "mode",
  "redirect",
  "referrer",
  "referrerPolicy",
  "keepalive"
].forEach((k) => {
  Object.defineProperty(requestPrototype, k, { get() {
    return this[getRequestCache]()[k];
  } });
});
["clone", "formData"].forEach((k) => {
  Object.defineProperty(requestPrototype, k, { value: function() {
    if (this[bodyConsumedDirectlyKey]) {
      if (k === "clone") throw newBodyUnusableError();
      return rejectBodyUnusable();
    }
    return this[getRequestCache]()[k]();
  } });
});
Object.defineProperty(requestPrototype, "text", { value: function() {
  return readBodyWithFastPath(this, "text", (buf) => textDecoder.decode(buf));
} });
Object.defineProperty(requestPrototype, "arrayBuffer", { value: function() {
  return readBodyWithFastPath(this, "arrayBuffer", (buf) => toArrayBuffer(buf));
} });
Object.defineProperty(requestPrototype, "blob", { value: function() {
  return readBodyWithFastPath(this, "blob", (buf, request) => {
    let type = contentType(request), init = type ? { headers: { "content-type": type } } : void 0;
    return new Response(buf, init).blob();
  });
} });
Object.defineProperty(requestPrototype, "json", { value: function() {
  return this[bodyConsumedDirectlyKey] ? rejectBodyUnusable() : this.text().then(JSON.parse);
} });
Object.defineProperty(requestPrototype, Symbol.for("nodejs.util.inspect.custom"), { value: function(depth, options, inspectFn) {
  return `Request (lightweight) ${inspectFn({
    method: this.method,
    url: this.url,
    headers: this.headers,
    nativeRequest: this[requestCache]
  }, {
    ...options,
    depth: depth == null ? null : depth - 1
  })}`;
} });
Object.setPrototypeOf(requestPrototype, Request$1.prototype);
var newRequest = (incoming, defaultHostname) => {
  let req = Object.create(requestPrototype);
  req[incomingKey] = incoming, req[methodKey] = normalizeIncomingMethod(incoming.method);
  let incomingUrl = incoming.url || "";
  if (incomingUrl[0] !== "/" && (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://"))) {
    if (incoming instanceof Http2ServerRequest) throw new RequestError("Absolute URL for :path is not allowed in HTTP/2");
    try {
      validateRequestTarget(incomingUrl), req[urlKey] = new URL(incomingUrl).href;
    } catch (e) {
      throw new RequestError("Invalid absolute URL", { cause: e });
    }
    return req;
  }
  let host = (incoming instanceof Http2ServerRequest ? incoming.authority : incoming.headers.host) || defaultHostname;
  if (!host) throw new RequestError("Missing host header");
  let scheme;
  if (incoming instanceof Http2ServerRequest) {
    if (scheme = incoming.scheme, !(scheme === "http" || scheme === "https")) throw new RequestError("Unsupported scheme");
  } else scheme = incoming.socket && incoming.socket.encrypted ? "https" : "http";
  try {
    req[urlKey] = buildUrl(scheme, host, incomingUrl);
  } catch (e) {
    throw e instanceof RequestError ? e : new RequestError("Invalid URL", { cause: e });
  }
  return req;
}, defaultContentType = "text/plain; charset=UTF-8", responseCache = Symbol("responseCache"), getResponseCache = Symbol("getResponseCache"), cacheKey = Symbol("cache"), GlobalResponse = global.Response, Response$1 = class Response$12 {
  #body;
  #init;
  [getResponseCache]() {
    let cache = this[cacheKey], liveHeaders = cache && cache[2] instanceof Headers ? cache[2] : void 0;
    return delete this[cacheKey], this[responseCache] ||= new GlobalResponse(this.#body, liveHeaders ? {
      status: this.#init?.status,
      statusText: this.#init?.statusText,
      headers: liveHeaders
    } : this.#init);
  }
  constructor(body, init) {
    let headers;
    if (this.#body = body, init instanceof GlobalResponse) {
      let cachedGlobalResponse = init[responseCache];
      if (cachedGlobalResponse) {
        this.#init = cachedGlobalResponse, this[getResponseCache]();
        return;
      }
      this.#init = init instanceof Response$12 ? init.#init : init, headers = new Headers(init.headers);
    } else this.#init = init;
    (body == null || typeof body == "string" || typeof body?.getReader < "u" || body instanceof Blob || body instanceof Uint8Array) && (this[cacheKey] = [
      init?.status || 200,
      body ?? null,
      headers || init?.headers
    ]);
  }
  get headers() {
    let cache = this[cacheKey];
    return cache ? (cache[2] instanceof Headers || (cache[2] = new Headers(cache[2] || (cache[1] === null ? void 0 : { "content-type": defaultContentType }))), cache[2]) : this[getResponseCache]().headers;
  }
  get status() {
    return this[cacheKey]?.[0] ?? this[getResponseCache]().status;
  }
  get ok() {
    let status = this.status;
    return status >= 200 && status < 300;
  }
};
[
  "body",
  "bodyUsed",
  "redirected",
  "statusText",
  "trailers",
  "type",
  "url"
].forEach((k) => {
  Object.defineProperty(Response$1.prototype, k, { get() {
    return this[getResponseCache]()[k];
  } });
});
[
  "arrayBuffer",
  "blob",
  "clone",
  "formData",
  "json",
  "text"
].forEach((k) => {
  Object.defineProperty(Response$1.prototype, k, { value: function() {
    return this[getResponseCache]()[k]();
  } });
});
Object.defineProperty(Response$1.prototype, Symbol.for("nodejs.util.inspect.custom"), { value: function(depth, options, inspectFn) {
  return `Response (lightweight) ${inspectFn({
    status: this.status,
    headers: this.headers,
    ok: this.ok,
    nativeResponse: this[responseCache]
  }, {
    ...options,
    depth: depth == null ? null : depth - 1
  })}`;
} });
Object.setPrototypeOf(Response$1, GlobalResponse);
Object.setPrototypeOf(Response$1.prototype, GlobalResponse.prototype);
var validRedirectUrl = /^https?:\/\/[!#-;=?-[\]_a-z~A-Z]+$/, parseRedirectUrl = (url) => url instanceof URL ? url.href : validRedirectUrl.test(url) ? url : new URL(url).href, validRedirectStatuses = /* @__PURE__ */ new Set([
  301,
  302,
  303,
  307,
  308
]);
Object.defineProperty(Response$1, "redirect", {
  value: function(url, status = 302) {
    if (!validRedirectStatuses.has(status)) throw new RangeError("Invalid status code");
    return new Response$1(null, {
      status,
      headers: { location: parseRedirectUrl(url) }
    });
  },
  writable: !0,
  configurable: !0
});
Object.defineProperty(Response$1, "json", {
  value: function(data, init) {
    let body = JSON.stringify(data);
    if (body === void 0) throw new TypeError("The data is not JSON serializable");
    let initHeaders = init?.headers, headers;
    return initHeaders ? (headers = new Headers(initHeaders), headers.has("content-type") || headers.set("content-type", "application/json")) : headers = { "content-type": "application/json" }, new Response$1(body, {
      status: init?.status ?? 200,
      statusText: init?.statusText,
      headers
    });
  },
  writable: !0,
  configurable: !0
});
async function readWithoutBlocking(readPromise) {
  return Promise.race([readPromise, Promise.resolve().then(() => Promise.resolve(void 0))]);
}
function writeFromReadableStreamDefaultReader(reader, writable, currentReadPromise) {
  let cancel = (error2) => {
    reader.cancel(error2).catch(() => {
    });
  };
  return writable.on("close", cancel), writable.on("error", cancel), (currentReadPromise ?? reader.read()).then(flow, handleStreamError), reader.closed.finally(() => {
    writable.off("close", cancel), writable.off("error", cancel);
  });
  function handleStreamError(error2) {
    error2 && writable.destroy(error2);
  }
  function onDrain() {
    reader.read().then(flow, handleStreamError);
  }
  function flow({ done, value }) {
    try {
      if (done) writable.end();
      else if (!writable.write(value)) writable.once("drain", onDrain);
      else return reader.read().then(flow, handleStreamError);
    } catch (e) {
      handleStreamError(e);
    }
  }
}
function writeFromReadableStream(stream, writable) {
  if (stream.locked) throw new TypeError("ReadableStream is locked.");
  return writable.destroyed ? void 0 : writeFromReadableStreamDefaultReader(stream.getReader(), writable);
}
var buildOutgoingHttpHeaders = (headers, defaultContentType2) => {
  let res = {};
  if (headers instanceof Headers || (headers = new Headers(headers ?? void 0)), headers.has("set-cookie")) {
    let cookies = [];
    for (let [k, v] of headers) k === "set-cookie" ? cookies.push(v) : res[k] = v;
    cookies.length > 0 && (res["set-cookie"] = cookies);
  } else for (let [k, v] of headers) res[k] = v;
  return defaultContentType2 && (res["content-type"] ??= defaultContentType2), res;
}, outgoingEnded = Symbol("outgoingEnded"), incomingDraining = Symbol("incomingDraining"), DRAIN_TIMEOUT_MS = 500, MAX_DRAIN_BYTES = 64 * 1024 * 1024, drainIncoming = (incoming) => {
  let incomingWithDrainState = incoming;
  if (incoming.destroyed || incomingWithDrainState[incomingDraining]) return;
  if (incomingWithDrainState[incomingDraining] = !0, incoming instanceof Http2ServerRequest) {
    try {
      incoming.stream?.close?.(constants.NGHTTP2_NO_ERROR);
    } catch {
    }
    return;
  }
  let bytesRead = 0, cleanup = () => {
    clearTimeout(timer), incoming.off("data", onData), incoming.off("end", cleanup), incoming.off("error", cleanup);
  }, forceClose = () => {
    cleanup();
    let socket = incoming.socket;
    socket && !socket.destroyed && (typeof socket.destroySoon == "function" ? socket.destroySoon() : typeof socket.destroy == "function" && socket.destroy());
  }, timer = setTimeout(forceClose, DRAIN_TIMEOUT_MS);
  timer.unref?.();
  let onData = (chunk) => {
    bytesRead += chunk.length, bytesRead > MAX_DRAIN_BYTES && forceClose();
  };
  incoming.on("data", onData), incoming.on("end", cleanup), incoming.on("error", cleanup), incoming.resume();
}, makeCloseHandler = (req, incoming, outgoing, needsBodyCleanup) => () => {
  incoming.errored ? (recordBodyBufferedBeforeDisconnect(incoming), req[abortRequest](incoming.errored.toString())) : (!outgoing.writableFinished || incoming instanceof Http2ServerRequest && incoming.aborted) && (recordBodyBufferedBeforeDisconnect(incoming), req[abortRequest]("Client connection prematurely closed.")), needsBodyCleanup && !incoming.readableEnded && setTimeout(() => {
    incoming.readableEnded || setTimeout(() => {
      drainIncoming(incoming);
    });
  });
}, isImmediateCacheableResponse = (res) => {
  if (!(cacheKey in res)) return !1;
  let body = res[cacheKey][1];
  return body === null || typeof body == "string" || body instanceof Uint8Array;
}, handleRequestError = () => new Response(null, { status: 400 }), handleFetchError = (e) => new Response(null, { status: e instanceof Error && (e.name === "TimeoutError" || e.constructor.name === "TimeoutError") ? 504 : 500 }), handleResponseError = (e, outgoing) => {
  let err = e instanceof Error ? e : new Error("unknown error", { cause: e });
  err.code === "ERR_STREAM_PREMATURE_CLOSE" ? console.info("The user aborted a request.") : (console.error(e), outgoing.headersSent || (outgoing instanceof ServerResponse && (outgoing._contentLength = null), outgoing.writeHead(500, { "Content-Type": "text/plain" })), outgoing.end(`Error: ${err.message}`), outgoing.destroy(err));
}, flushHeaders = (outgoing) => {
  "flushHeaders" in outgoing && outgoing.writable && outgoing.flushHeaders();
}, trySetContentLength = (outgoing, status, length) => {
  let http1 = outgoing;
  return http1._contentLength === null && http1._hasBody && http1.useChunkedEncodingByDefault && !http1._removedContLen && status >= 200 && status !== 204 && status !== 304 && !outgoing.hasHeader("content-length") && !outgoing.hasHeader("transfer-encoding") && !outgoing.hasHeader("trailer") ? (http1._contentLength = length, !0) : !1;
}, writeDefaultHeaders = (outgoing, status, length) => {
  trySetContentLength(outgoing, status, length) ? outgoing.writeHead(status, { "Content-Type": defaultContentType }) : outgoing.writeHead(status, {
    "Content-Type": defaultContentType,
    "Content-Length": length
  });
}, responseViaCache = async (res, outgoing) => {
  let [status, body, header] = res[cacheKey];
  if (!header) {
    body === null ? (outgoing.writeHead(status), outgoing.end()) : typeof body == "string" ? (writeDefaultHeaders(outgoing, status, Buffer.byteLength(body)), outgoing.end(body)) : body instanceof Uint8Array ? (writeDefaultHeaders(outgoing, status, body.byteLength), outgoing.end(body)) : body instanceof Blob ? (writeDefaultHeaders(outgoing, status, body.size), outgoing.end(new Uint8Array(await body.arrayBuffer()))) : (outgoing.writeHead(status, { "Content-Type": defaultContentType }), flushHeaders(outgoing), await writeFromReadableStream(body, outgoing)?.catch((e) => handleResponseError(e, outgoing))), outgoing[outgoingEnded]?.();
    return;
  }
  let hasContentLength = !1, plainHeaders = !1, canAutoLength = !0;
  if (header instanceof Headers)
    hasContentLength = header.has("content-length"), header = buildOutgoingHttpHeaders(header, body === null ? void 0 : defaultContentType);
  else if (Array.isArray(header)) {
    let headerObj = new Headers(header);
    hasContentLength = headerObj.has("content-length"), header = buildOutgoingHttpHeaders(headerObj, body === null ? void 0 : defaultContentType);
  } else {
    plainHeaders = !0;
    for (let key in header) {
      if (key.length === 14 && key.toLowerCase() === "content-length") {
        hasContentLength = !0;
        break;
      }
      (key.length === 17 && key.toLowerCase() === "transfer-encoding" || key.length === 7 && key.toLowerCase() === "trailer") && (canAutoLength = !1);
    }
  }
  if (!hasContentLength) {
    let length;
    typeof body == "string" ? length = Buffer.byteLength(body) : body instanceof Uint8Array ? length = body.byteLength : body instanceof Blob && (length = body.size), length !== void 0 && (!plainHeaders || !canAutoLength || !trySetContentLength(outgoing, status, length)) && (plainHeaders && (header = { ...header }), header["Content-Length"] = length);
  }
  outgoing.writeHead(status, header), body == null ? outgoing.end() : typeof body == "string" || body instanceof Uint8Array ? outgoing.end(body) : body instanceof Blob ? outgoing.end(new Uint8Array(await body.arrayBuffer())) : (flushHeaders(outgoing), await writeFromReadableStream(body, outgoing)?.catch((e) => handleResponseError(e, outgoing))), outgoing[outgoingEnded]?.();
}, isPromise = (res) => typeof res.then == "function", responseViaResponseObject = async (res, outgoing, options = {}) => {
  if (isPromise(res)) if (options.errorHandler) try {
    res = await res;
  } catch (err) {
    let errRes = await options.errorHandler(err);
    if (!errRes) return;
    res = errRes;
  }
  else res = await res.catch(handleFetchError);
  if (cacheKey in res) return responseViaCache(res, outgoing);
  let resHeaderRecord = buildOutgoingHttpHeaders(res.headers, res.body === null ? void 0 : defaultContentType);
  if (res.body) {
    let reader = res.body.getReader(), values = [], done = !1, currentReadPromise;
    if (resHeaderRecord["transfer-encoding"] !== "chunked") {
      let maxReadCount = 2;
      for (let i = 0; i < maxReadCount; i++) {
        currentReadPromise ||= reader.read();
        let chunk = await readWithoutBlocking(currentReadPromise).catch((e) => {
          console.error(e), done = !0;
        });
        if (!chunk) {
          if (i === 1) {
            await new Promise((resolve) => setTimeout(resolve)), maxReadCount = 3;
            continue;
          }
          break;
        }
        if (currentReadPromise = void 0, chunk.value && values.push(chunk.value), chunk.done) {
          done = !0;
          break;
        }
      }
      done && !("content-length" in resHeaderRecord) && (resHeaderRecord["content-length"] = values.reduce((acc, value) => acc + value.length, 0));
    }
    outgoing.writeHead(res.status, resHeaderRecord), values.forEach((value) => {
      outgoing.write(value);
    }), done ? outgoing.end() : (values.length === 0 && flushHeaders(outgoing), await writeFromReadableStreamDefaultReader(reader, outgoing, currentReadPromise));
  } else resHeaderRecord[X_ALREADY_SENT] || (outgoing.writeHead(res.status, resHeaderRecord), outgoing.end());
  outgoing[outgoingEnded]?.();
}, getRequestListener = (fetchCallback, options = {}) => {
  let autoCleanupIncoming = options.autoCleanupIncoming ?? !0;
  return options.overrideGlobalObjects !== !1 && global.Request !== Request$1 && (Object.defineProperty(global, "Request", { value: Request$1 }), Object.defineProperty(global, "Response", { value: Response$1 })), async (incoming, outgoing) => {
    let res, req, needsBodyCleanup = !1, closeHandlerAttached = !1, ensureCloseHandler = () => {
      !req || closeHandlerAttached || (closeHandlerAttached = !0, outgoing.on("close", makeCloseHandler(req, incoming, outgoing, needsBodyCleanup)));
    };
    try {
      if (req = newRequest(incoming, options.hostname), needsBodyCleanup = autoCleanupIncoming && !(incoming.method === "GET" || incoming.method === "HEAD"), needsBodyCleanup && (incoming[wrapBodyStream] = !0, incoming instanceof Http2ServerRequest && (outgoing[outgoingEnded] = () => {
        incoming.readableEnded || setTimeout(() => {
          incoming.readableEnded || setTimeout(() => {
            incoming.destroy(), outgoing.destroy();
          });
        });
      })), res = fetchCallback(req, {
        incoming,
        outgoing
      }), !isPromise(res) && isImmediateCacheableResponse(res))
        return needsBodyCleanup && !incoming.readableEnded && outgoing.once("finish", () => {
          incoming.readableEnded || drainIncoming(incoming);
        }), responseViaCache(res, outgoing);
      ensureCloseHandler();
    } catch (e) {
      if (res) return handleResponseError(e, outgoing);
      if (options.errorHandler) {
        if (ensureCloseHandler(), res = await options.errorHandler(req ? e : toRequestError(e)), !res) return;
      } else req ? res = handleFetchError(e) : res = handleRequestError();
    }
    try {
      return await responseViaResponseObject(res, outgoing, options);
    } catch (e) {
      return handleResponseError(e, outgoing);
    }
  };
}, CloseEvent = globalThis.CloseEvent ?? class extends Event {
  #eventInitDict;
  constructor(type, eventInitDict = {}) {
    super(type, eventInitDict), this.#eventInitDict = eventInitDict;
  }
  get wasClean() {
    return this.#eventInitDict.wasClean ?? !1;
  }
  get code() {
    return this.#eventInitDict.code ?? 0;
  }
  get reason() {
    return this.#eventInitDict.reason ?? "";
  }
}, ErrorEvent = globalThis.ErrorEvent ?? class extends Event {
  #eventInitDict;
  constructor(type, eventInitDict = {}) {
    super(type, eventInitDict), this.#eventInitDict = eventInitDict;
  }
  get message() {
    return this.#eventInitDict.message ?? "";
  }
  get filename() {
    return this.#eventInitDict.filename ?? "";
  }
  get lineno() {
    return this.#eventInitDict.lineno ?? 0;
  }
  get colno() {
    return this.#eventInitDict.colno ?? 0;
  }
  get error() {
    return this.#eventInitDict.error ?? null;
  }
}, generateConnectionSymbol = () => Symbol("connection"), CONNECTION_SYMBOL_KEY = Symbol("CONNECTION_SYMBOL_KEY"), WAIT_FOR_WEBSOCKET_SYMBOL = Symbol("WAIT_FOR_WEBSOCKET_SYMBOL");
var upgradeWebSocket = defineWebSocketHelper(async (c, events, options) => {
  if (c.req.header("upgrade")?.toLowerCase() !== "websocket") return;
  let env2 = c.env, waitForWebSocket = env2[WAIT_FOR_WEBSOCKET_SYMBOL];
  if (!waitForWebSocket || !env2.incoming) return new Response(null, { status: 500 });
  let connectionSymbol = generateConnectionSymbol();
  return env2[CONNECTION_SYMBOL_KEY] = connectionSymbol, (async () => {
    let ws;
    try {
      ws = await waitForWebSocket(env2.incoming, connectionSymbol);
    } catch {
      return;
    }
    let messagesReceivedInStarting = [], bufferMessage = (data, isBinary) => {
      messagesReceivedInStarting.push([data, isBinary]);
    };
    ws.on("message", bufferMessage);
    let ctx = {
      binaryType: "arraybuffer",
      close(code, reason) {
        ws.close(code, reason);
      },
      protocol: ws.protocol,
      raw: ws,
      get readyState() {
        return ws.readyState;
      },
      send(source, opts) {
        ws.send(source, { compress: opts?.compress });
      },
      url: new URL(c.req.url)
    };
    try {
      events?.onOpen?.(new Event("open"), ctx);
    } catch (e) {
      (options?.onError ?? console.error)(e);
    }
    let handleMessage = (data, isBinary) => {
      let datas = Array.isArray(data) ? data : [data];
      for (let data2 of datas) try {
        events?.onMessage?.(new MessageEvent("message", { data: isBinary ? data2 instanceof ArrayBuffer ? data2 : data2.buffer.slice(data2.byteOffset, data2.byteOffset + data2.byteLength) : typeof data2 == "string" ? data2 : Buffer.from(data2).toString("utf-8") }), ctx);
      } catch (e) {
        (options?.onError ?? console.error)(e);
      }
    };
    ws.off("message", bufferMessage);
    for (let message of messagesReceivedInStarting) handleMessage(...message);
    ws.on("message", (data, isBinary) => {
      handleMessage(data, isBinary);
    }), ws.on("close", (code, reason) => {
      try {
        events?.onClose?.(new CloseEvent("close", {
          code,
          reason: reason.toString()
        }), ctx);
      } catch (e) {
        (options?.onError ?? console.error)(e);
      }
    }), ws.on("error", (error2) => {
      try {
        events?.onError?.(new ErrorEvent("error", { error: error2 }), ctx);
      } catch (e) {
        (options?.onError ?? console.error)(e);
      }
    });
  })(), new Response();
});

// node_modules/@modelcontextprotocol/sdk/dist/esm/shared/mediaType.js
var import_content_type = __toESM(require_content_type(), 1);
function mediaTypeEssence(header) {
  if (header)
    try {
      return import_content_type.default.parse(header).type;
    } catch {
      let essence = (header.split(";", 1)[0] ?? "").trim().toLowerCase();
      return essence === "" || header.slice(essence.length).includes(",") ? void 0 : essence;
    }
}
function isJsonContentType(header) {
  return header === "application/json" ? !0 : mediaTypeEssence(header) === "application/json";
}

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/requestBody.js
function requestBodyTooLargeMessage(maxBytes) {
  return `Payload Too Large: Request body must not exceed ${maxBytes} bytes`;
}
function resolveMaxRequestBodySize(value) {
  if (value === void 0)
    return 4194304;
  if (typeof value != "number" || !Number.isFinite(value) || value <= 0)
    throw new RangeError(`maxRequestBodySize must be a positive number of bytes, got ${String(value)}`);
  return value;
}
async function readRequestBody(request, maxBytes = 4194304) {
  if (Number(request.headers.get("content-length")) > maxBytes)
    return { tooLarge: !0 };
  if (request.body === null)
    return { tooLarge: !1, text: "" };
  let reader = request.body.getReader(), decoder = new TextDecoder(), received = 0, text = "";
  try {
    for (; ; ) {
      let { done, value } = await reader.read();
      if (done)
        break;
      if (received += value.byteLength, received > maxBytes)
        return { tooLarge: !0 };
      text += decoder.decode(value, { stream: !0 });
    }
  } finally {
    reader.releaseLock();
  }
  return { tooLarge: !1, text: text + decoder.decode() };
}

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/sseKeepAlive.js
function armSseKeepAlive(intervalMs, onTick) {
  if (!Number.isFinite(intervalMs) || intervalMs < 1)
    return;
  let timer = setInterval(onTick, Math.min(intervalMs, 2147483647));
  return timer.unref?.(), timer;
}

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/webStandardStreamableHttp.js
var WebStandardStreamableHTTPServerTransport = class {
  constructor(options = {}) {
    this._started = !1, this._hasHandledRequest = !1, this._streamMapping = /* @__PURE__ */ new Map(), this._requestToStreamMapping = /* @__PURE__ */ new Map(), this._resumableStreams = /* @__PURE__ */ new Set(), this._requestResponseMap = /* @__PURE__ */ new Map(), this._initialized = !1, this._enableJsonResponse = !1, this._standaloneSseStreamId = "_GET_stream", this._closed = !1, this.sessionIdGenerator = options.sessionIdGenerator, this._enableJsonResponse = options.enableJsonResponse ?? !1, this._eventStore = options.eventStore, this._onsessioninitialized = options.onsessioninitialized, this._onsessionclosed = options.onsessionclosed, this._allowedHosts = options.allowedHosts, this._allowedOrigins = options.allowedOrigins, this._enableDnsRebindingProtection = options.enableDnsRebindingProtection ?? !1, this._retryInterval = options.retryInterval, this._keepAliveMs = options.keepAliveMs ?? 15e3, this._maxRequestBodySize = resolveMaxRequestBodySize(options.maxRequestBodySize);
  }
  /**
   * Arms a keep-alive interval for an SSE stream that periodically writes an SSE
   * comment frame so intermediaries and idle timeouts don't kill the connection.
   * The returned timer is owned by the stream it was armed for: the stream's
   * cancel/cleanup callbacks must clear it. The interval clears itself if a
   * write fails (stream already closed/cancelled).
   */
  startKeepAlive(controller, encoder) {
    if (this._closed)
      return;
    let timer = armSseKeepAlive(this._keepAliveMs, () => {
      try {
        controller.enqueue(encoder.encode(`: keepalive

`));
      } catch {
        timer !== void 0 && clearInterval(timer);
      }
    });
    return timer;
  }
  /**
   * Starts the transport. This is required by the Transport interface but is a no-op
   * for the Streamable HTTP transport as connections are managed per-request.
   */
  async start() {
    if (this._started)
      throw new Error("Transport already started");
    this._started = !0;
  }
  /**
   * Helper to create a JSON error response
   */
  createJsonErrorResponse(status, code, message, options) {
    let error2 = { code, message };
    return options?.data !== void 0 && (error2.data = options.data), new Response(JSON.stringify({
      jsonrpc: "2.0",
      error: error2,
      id: null
    }), {
      status,
      headers: {
        "Content-Type": "application/json",
        ...options?.headers
      }
    });
  }
  /**
   * Validates request headers for DNS rebinding protection.
   * @returns Error response if validation fails, undefined if validation passes.
   */
  validateRequestHeaders(req) {
    if (this._enableDnsRebindingProtection) {
      if (this._allowedHosts && this._allowedHosts.length > 0) {
        let hostHeader = req.headers.get("host");
        if (!hostHeader || !this._allowedHosts.includes(hostHeader)) {
          let error2 = `Invalid Host header: ${hostHeader}`;
          return this.onerror?.(new Error(error2)), this.createJsonErrorResponse(403, -32e3, error2);
        }
      }
      if (this._allowedOrigins && this._allowedOrigins.length > 0) {
        let originHeader = req.headers.get("origin");
        if (originHeader && !this._allowedOrigins.includes(originHeader)) {
          let error2 = `Invalid Origin header: ${originHeader}`;
          return this.onerror?.(new Error(error2)), this.createJsonErrorResponse(403, -32e3, error2);
        }
      }
    }
  }
  /**
   * Handles an incoming HTTP request, whether GET, POST, or DELETE
   * Returns a Response object (Web Standard)
   */
  async handleRequest(req, options) {
    if (this._closed)
      return this.createJsonErrorResponse(404, -32001, "Session not found");
    if (!this.sessionIdGenerator && this._hasHandledRequest)
      throw new Error("Stateless transport cannot be reused across requests. Create a new transport per request.");
    this._hasHandledRequest = !0;
    let validationError = this.validateRequestHeaders(req);
    if (validationError)
      return validationError;
    switch (req.method) {
      case "POST":
        return this.handlePostRequest(req, options);
      case "GET":
        return this.handleGetRequest(req);
      case "DELETE":
        return this.handleDeleteRequest(req);
      default:
        return this.handleUnsupportedRequest();
    }
  }
  /**
   * Writes a priming event to establish resumption capability.
   * Only sends if eventStore is configured (opt-in for resumability) and
   * the client's protocol version supports empty SSE data (>= 2025-11-25).
   */
  async writePrimingEvent(controller, encoder, streamId, protocolVersion) {
    if (!this._eventStore || protocolVersion < "2025-11-25")
      return;
    let primingEventId = await this._eventStore.storeEvent(streamId, {}), primingEvent = `id: ${primingEventId}
data: 

`;
    this._retryInterval !== void 0 && (primingEvent = `id: ${primingEventId}
retry: ${this._retryInterval}
data: 

`), controller.enqueue(encoder.encode(primingEvent)), this._resumableStreams.add(streamId);
  }
  /**
   * Handles GET requests for SSE stream
   */
  async handleGetRequest(req) {
    if (!req.headers.get("accept")?.includes("text/event-stream"))
      return this.onerror?.(new Error("Not Acceptable: Client must accept text/event-stream")), this.createJsonErrorResponse(406, -32e3, "Not Acceptable: Client must accept text/event-stream");
    let sessionError = this.validateSession(req);
    if (sessionError)
      return sessionError;
    let protocolError = this.validateProtocolVersion(req);
    if (protocolError)
      return protocolError;
    if (this._eventStore) {
      let lastEventId = req.headers.get("last-event-id");
      if (lastEventId)
        return this.replayEvents(lastEventId);
    }
    if (this._streamMapping.get(this._standaloneSseStreamId) !== void 0)
      return this.onerror?.(new Error("Conflict: Only one SSE stream is allowed per session")), this.createJsonErrorResponse(409, -32e3, "Conflict: Only one SSE stream is allowed per session");
    let encoder = new TextEncoder(), streamController, keepAliveTimer, readable = new ReadableStream({
      start: (controller) => {
        streamController = controller;
      },
      cancel: () => {
        keepAliveTimer !== void 0 && clearInterval(keepAliveTimer), this._streamMapping.get(this._standaloneSseStreamId)?.controller === streamController && this._streamMapping.delete(this._standaloneSseStreamId);
      }
    }), headers = {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no"
    };
    return this.sessionId !== void 0 && (headers["mcp-session-id"] = this.sessionId), this._streamMapping.set(this._standaloneSseStreamId, {
      controller: streamController,
      encoder,
      cleanup: () => {
        keepAliveTimer !== void 0 && clearInterval(keepAliveTimer), this._streamMapping.delete(this._standaloneSseStreamId);
        try {
          streamController.close();
        } catch {
        }
      }
    }), keepAliveTimer = this.startKeepAlive(streamController, encoder), new Response(readable, { headers });
  }
  /**
   * Replays events that would have been sent after the specified event ID
   * Only used when resumability is enabled
   */
  async replayEvents(lastEventId) {
    if (!this._eventStore)
      return this.onerror?.(new Error("Event store not configured")), this.createJsonErrorResponse(400, -32e3, "Event store not configured");
    try {
      let streamId;
      if (this._eventStore.getStreamIdForEventId) {
        if (streamId = await this._eventStore.getStreamIdForEventId(lastEventId), !streamId)
          return this.onerror?.(new Error("Invalid event ID format")), this.createJsonErrorResponse(400, -32e3, "Invalid event ID format");
        if (this._streamMapping.get(streamId) !== void 0)
          return this.onerror?.(new Error("Conflict: Stream already has an active connection")), this.createJsonErrorResponse(409, -32e3, "Conflict: Stream already has an active connection");
      }
      let headers = {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache, no-transform",
        Connection: "keep-alive",
        "X-Accel-Buffering": "no"
      };
      this.sessionId !== void 0 && (headers["mcp-session-id"] = this.sessionId);
      let encoder = new TextEncoder(), streamController, keepAliveTimer, replayedStreamId, cancelled = !1, readable = new ReadableStream({
        start: (controller) => {
          streamController = controller;
        },
        cancel: () => {
          cancelled = !0, keepAliveTimer !== void 0 && clearInterval(keepAliveTimer), replayedStreamId !== void 0 && this._streamMapping.get(replayedStreamId)?.controller === streamController && this._streamMapping.delete(replayedStreamId);
        }
      }), replayedEventIds = /* @__PURE__ */ new Set();
      if (replayedStreamId = await this._eventStore.replayEventsAfter(lastEventId, {
        send: async (eventId, message) => {
          if (this.writeSSEEvent(streamController, encoder, message, eventId))
            replayedEventIds.add(eventId);
          else {
            this.onerror?.(new Error("Failed replay events"));
            try {
              streamController.close();
            } catch {
            }
          }
        }
      }), this._closed || cancelled) {
        try {
          streamController.close();
        } catch {
        }
        return this.createJsonErrorResponse(404, -32001, "Session not found");
      }
      return this._streamMapping.get(replayedStreamId)?.cleanup(), this._streamMapping.set(replayedStreamId, {
        controller: streamController,
        encoder,
        replayedEventIds,
        cleanup: () => {
          keepAliveTimer !== void 0 && clearInterval(keepAliveTimer), this._streamMapping.delete(replayedStreamId);
          try {
            streamController.close();
          } catch {
          }
        }
      }), this._resumableStreams.add(replayedStreamId), keepAliveTimer = this.startKeepAlive(streamController, encoder), new Response(readable, { headers });
    } catch (error2) {
      return this.onerror?.(error2), this.createJsonErrorResponse(500, -32e3, "Error replaying events");
    }
  }
  /**
   * Writes an event to an SSE stream via controller with proper formatting
   */
  writeSSEEvent(controller, encoder, message, eventId) {
    try {
      let eventData = `event: message
`;
      return eventId && (eventData += `id: ${eventId}
`), eventData += `data: ${JSON.stringify(message)}

`, controller.enqueue(encoder.encode(eventData)), !0;
    } catch (error2) {
      return this.onerror?.(error2), !1;
    }
  }
  /**
   * Handles unsupported requests (PUT, PATCH, etc.)
   */
  handleUnsupportedRequest() {
    return this.onerror?.(new Error("Method not allowed.")), new Response(JSON.stringify({
      jsonrpc: "2.0",
      error: {
        code: -32e3,
        message: "Method not allowed."
      },
      id: null
    }), {
      status: 405,
      headers: {
        Allow: "GET, POST, DELETE",
        "Content-Type": "application/json"
      }
    });
  }
  /**
   * Handles POST requests containing JSON-RPC messages
   */
  async handlePostRequest(req, options) {
    try {
      let acceptHeader = req.headers.get("accept");
      if (!acceptHeader?.includes("application/json") || !acceptHeader.includes("text/event-stream"))
        return this.onerror?.(new Error("Not Acceptable: Client must accept both application/json and text/event-stream")), this.createJsonErrorResponse(406, -32e3, "Not Acceptable: Client must accept both application/json and text/event-stream");
      let ct = req.headers.get("content-type");
      if (!isJsonContentType(ct))
        return this.onerror?.(new Error("Unsupported Media Type: Content-Type must be application/json")), this.createJsonErrorResponse(415, -32e3, "Unsupported Media Type: Content-Type must be application/json");
      let requestInfo = {
        headers: Object.fromEntries(req.headers.entries()),
        url: new URL(req.url)
      }, rawMessage;
      if (options?.parsedBody !== void 0)
        rawMessage = options.parsedBody;
      else
        try {
          let body = await readRequestBody(req, this._maxRequestBodySize);
          if (body.tooLarge) {
            let message = requestBodyTooLargeMessage(this._maxRequestBodySize);
            return this.onerror?.(new Error(message)), this.createJsonErrorResponse(413, -32e3, message);
          }
          rawMessage = JSON.parse(body.text);
        } catch {
          return this.onerror?.(new Error("Parse error: Invalid JSON")), this.createJsonErrorResponse(400, -32700, "Parse error: Invalid JSON");
        }
      if (Array.isArray(rawMessage) && rawMessage.length > 100)
        return this.onerror?.(new Error(`Invalid Request: Batch must not exceed ${100} messages`)), this.createJsonErrorResponse(400, -32600, `Invalid Request: Batch must not exceed ${100} messages`);
      let messages;
      try {
        Array.isArray(rawMessage) ? messages = rawMessage.map((msg) => JSONRPCMessageSchema.parse(msg)) : messages = [JSONRPCMessageSchema.parse(rawMessage)];
      } catch {
        return this.onerror?.(new Error("Parse error: Invalid JSON-RPC message")), this.createJsonErrorResponse(400, -32700, "Parse error: Invalid JSON-RPC message");
      }
      if (this._closed)
        return this.createJsonErrorResponse(404, -32001, "Session not found");
      let isInitializationRequest = messages.some(isInitializeRequest);
      if (isInitializationRequest) {
        if (this._initialized && this.sessionId !== void 0)
          return this.onerror?.(new Error("Invalid Request: Server already initialized")), this.createJsonErrorResponse(400, -32600, "Invalid Request: Server already initialized");
        if (messages.length > 1)
          return this.onerror?.(new Error("Invalid Request: Only one initialization request is allowed")), this.createJsonErrorResponse(400, -32600, "Invalid Request: Only one initialization request is allowed");
        this.sessionId = this.sessionIdGenerator?.(), this._initialized = !0, this.sessionId && this._onsessioninitialized && await Promise.resolve(this._onsessioninitialized(this.sessionId));
      }
      if (!isInitializationRequest) {
        let sessionError = this.validateSession(req);
        if (sessionError)
          return sessionError;
        let protocolError = this.validateProtocolVersion(req);
        if (protocolError)
          return protocolError;
      }
      if (this._closed)
        return this.createJsonErrorResponse(404, -32001, "Session not found");
      if (!messages.some(isJSONRPCRequest)) {
        for (let message of messages)
          this.onmessage?.(message, { authInfo: options?.authInfo, requestInfo });
        return new Response(null, { status: 202 });
      }
      let streamId = crypto.randomUUID(), initRequest = messages.find((m) => isInitializeRequest(m)), clientProtocolVersion = initRequest ? initRequest.params.protocolVersion : req.headers.get("mcp-protocol-version") ?? DEFAULT_NEGOTIATED_PROTOCOL_VERSION;
      if (this._enableJsonResponse)
        return new Promise((resolve) => {
          this._streamMapping.set(streamId, {
            resolveJson: resolve,
            cleanup: () => {
              this._streamMapping.delete(streamId);
            }
          });
          for (let message of messages)
            isJSONRPCRequest(message) && this._requestToStreamMapping.set(message.id, streamId);
          for (let message of messages)
            this.onmessage?.(message, { authInfo: options?.authInfo, requestInfo });
        });
      let encoder = new TextEncoder(), streamController, keepAliveTimer, readable = new ReadableStream({
        start: (controller) => {
          streamController = controller;
        },
        cancel: () => {
          keepAliveTimer !== void 0 && clearInterval(keepAliveTimer), this._streamMapping.get(streamId)?.controller === streamController && this._streamMapping.delete(streamId);
        }
      }), headers = {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache, no-transform",
        Connection: "keep-alive",
        "X-Accel-Buffering": "no"
      };
      this.sessionId !== void 0 && (headers["mcp-session-id"] = this.sessionId);
      for (let message of messages)
        isJSONRPCRequest(message) && (this._streamMapping.set(streamId, {
          controller: streamController,
          encoder,
          cleanup: () => {
            keepAliveTimer !== void 0 && clearInterval(keepAliveTimer), this._streamMapping.delete(streamId);
            try {
              streamController.close();
            } catch {
            }
          }
        }), this._requestToStreamMapping.set(message.id, streamId));
      try {
        await this.writePrimingEvent(streamController, encoder, streamId, clientProtocolVersion);
        for (let message of messages) {
          let closeSSEStream, closeStandaloneSSEStream;
          isJSONRPCRequest(message) && this._eventStore && clientProtocolVersion >= "2025-11-25" && (closeSSEStream = () => {
            this.closeSSEStream(message.id);
          }, closeStandaloneSSEStream = () => {
            this.closeStandaloneSSEStream();
          }), this.onmessage?.(message, { authInfo: options?.authInfo, requestInfo, closeSSEStream, closeStandaloneSSEStream });
        }
      } catch (error2) {
        this._streamMapping.get(streamId)?.cleanup(), this._resumableStreams.delete(streamId);
        for (let message of messages)
          isJSONRPCRequest(message) && this._requestToStreamMapping.delete(message.id);
        throw error2;
      }
      return this._streamMapping.get(streamId)?.controller === streamController && (keepAliveTimer = this.startKeepAlive(streamController, encoder)), new Response(readable, { status: 200, headers });
    } catch (error2) {
      return this.onerror?.(error2), this.createJsonErrorResponse(400, -32700, "Parse error", { data: String(error2) });
    }
  }
  /**
   * Handles DELETE requests to terminate sessions
   */
  async handleDeleteRequest(req) {
    let sessionError = this.validateSession(req);
    if (sessionError)
      return sessionError;
    let protocolError = this.validateProtocolVersion(req);
    if (protocolError)
      return protocolError;
    try {
      return await Promise.resolve(this._onsessionclosed?.(this.sessionId)), new Response(null, { status: 200 });
    } finally {
      await this.close();
    }
  }
  /**
   * Validates session ID for non-initialization requests.
   * Returns Response error if invalid, undefined otherwise
   */
  validateSession(req) {
    if (this.sessionIdGenerator === void 0)
      return;
    if (!this._initialized)
      return this.onerror?.(new Error("Bad Request: Server not initialized")), this.createJsonErrorResponse(400, -32e3, "Bad Request: Server not initialized");
    let sessionId = req.headers.get("mcp-session-id");
    if (!sessionId)
      return this.onerror?.(new Error("Bad Request: Mcp-Session-Id header is required")), this.createJsonErrorResponse(400, -32e3, "Bad Request: Mcp-Session-Id header is required");
    if (sessionId !== this.sessionId)
      return this.onerror?.(new Error("Session not found")), this.createJsonErrorResponse(404, -32001, "Session not found");
  }
  /**
   * Validates the MCP-Protocol-Version header on incoming requests.
   *
   * For initialization: Version negotiation handles unknown versions gracefully
   * (server responds with its supported version).
   *
   * For subsequent requests with MCP-Protocol-Version header:
   * - Accept if in supported list
   * - 400 if unsupported
   *
   * For HTTP requests without the MCP-Protocol-Version header:
   * - Accept and default to the version negotiated at initialization
   */
  validateProtocolVersion(req) {
    let protocolVersion = req.headers.get("mcp-protocol-version");
    if (protocolVersion !== null && !SUPPORTED_PROTOCOL_VERSIONS.includes(protocolVersion))
      return this.onerror?.(new Error(`Bad Request: Unsupported protocol version: ${protocolVersion} (supported versions: ${SUPPORTED_PROTOCOL_VERSIONS.join(", ")})`)), this.createJsonErrorResponse(400, -32e3, `Bad Request: Unsupported protocol version: ${protocolVersion} (supported versions: ${SUPPORTED_PROTOCOL_VERSIONS.join(", ")})`);
  }
  async close() {
    this._closed || (this._closed = !0, this._streamMapping.forEach(({ cleanup }) => {
      cleanup();
    }), this._streamMapping.clear(), this._requestResponseMap.clear(), this._resumableStreams.clear(), this.onclose?.());
  }
  /**
   * Close an SSE stream for a specific request, triggering client reconnection.
   * Use this to implement polling behavior during long-running operations -
   * client will reconnect after the retry interval specified in the priming event.
   */
  closeSSEStream(requestId) {
    let streamId = this._requestToStreamMapping.get(requestId);
    if (!streamId)
      return;
    let stream = this._streamMapping.get(streamId);
    stream && stream.cleanup();
  }
  /**
   * Close the standalone GET SSE stream, triggering client reconnection.
   * Use this to implement polling behavior for server-initiated notifications.
   */
  closeStandaloneSSEStream() {
    let stream = this._streamMapping.get(this._standaloneSseStreamId);
    stream && stream.cleanup();
  }
  async send(message, options) {
    let requestId = options?.relatedRequestId;
    if ((isJSONRPCResultResponse(message) || isJSONRPCErrorResponse(message)) && (requestId = message.id), requestId === void 0) {
      if (isJSONRPCResultResponse(message) || isJSONRPCErrorResponse(message))
        throw new Error("Cannot send a response on a standalone SSE stream unless resuming a previous client request");
      let eventId;
      this._eventStore && (eventId = await this._eventStore.storeEvent(this._standaloneSseStreamId, message));
      let standaloneSse = this._streamMapping.get(this._standaloneSseStreamId);
      if (standaloneSse === void 0)
        return;
      standaloneSse.controller && standaloneSse.encoder && (eventId === void 0 || !standaloneSse.replayedEventIds?.has(eventId)) && this.writeSSEEvent(standaloneSse.controller, standaloneSse.encoder, message, eventId);
      return;
    }
    let streamId = this._requestToStreamMapping.get(requestId);
    if (!streamId)
      throw new Error(`No connection established for request ID: ${String(requestId)}`);
    let stream = this._streamMapping.get(streamId);
    if (!this._enableJsonResponse) {
      let eventId;
      this._eventStore && (eventId = await this._eventStore.storeEvent(streamId, message), stream = this._streamMapping.get(streamId)), stream?.controller && stream?.encoder && (eventId === void 0 || !stream.replayedEventIds?.has(eventId)) && this.writeSSEEvent(stream.controller, stream.encoder, message, eventId) && eventId !== void 0 && this._resumableStreams.add(streamId);
    }
    if (isJSONRPCResultResponse(message) || isJSONRPCErrorResponse(message)) {
      this._requestResponseMap.set(requestId, message);
      let relatedIds = Array.from(this._requestToStreamMapping.entries()).filter(([_, sid]) => sid === streamId).map(([id]) => id);
      if (relatedIds.every((id) => this._requestResponseMap.has(id))) {
        if (!stream) {
          if (this._closed) {
            for (let id of relatedIds)
              this._requestResponseMap.delete(id), this._requestToStreamMapping.delete(id);
            return;
          }
          if (!this._enableJsonResponse && this._eventStore && this._resumableStreams.has(streamId)) {
            for (let id of relatedIds)
              this._requestResponseMap.delete(id), this._requestToStreamMapping.delete(id);
            this._resumableStreams.delete(streamId);
            return;
          }
          for (let id of relatedIds)
            this._requestResponseMap.delete(id), this._requestToStreamMapping.delete(id);
          throw new Error(`No connection established for request ID: ${String(requestId)}`);
        }
        if (this._enableJsonResponse && stream.resolveJson) {
          let headers = {
            "Content-Type": "application/json"
          };
          this.sessionId !== void 0 && (headers["mcp-session-id"] = this.sessionId);
          let responses = relatedIds.map((id) => this._requestResponseMap.get(id));
          responses.length === 1 ? stream.resolveJson(new Response(JSON.stringify(responses[0]), { status: 200, headers })) : stream.resolveJson(new Response(JSON.stringify(responses), { status: 200, headers }));
        } else
          stream.cleanup();
        for (let id of relatedIds)
          this._requestResponseMap.delete(id), this._requestToStreamMapping.delete(id);
        this._resumableStreams.delete(streamId);
      }
    }
  }
};

// node_modules/@modelcontextprotocol/sdk/dist/esm/server/streamableHttp.js
var StreamableHTTPServerTransport = class {
  constructor(options = {}) {
    this._requestContext = /* @__PURE__ */ new WeakMap(), this._webStandardTransport = new WebStandardStreamableHTTPServerTransport(options), this._requestListener = getRequestListener(async (webRequest) => {
      let context = this._requestContext.get(webRequest);
      return this._webStandardTransport.handleRequest(webRequest, {
        authInfo: context?.authInfo,
        parsedBody: context?.parsedBody
      });
    }, { overrideGlobalObjects: !1 });
  }
  /**
   * Gets the session ID for this transport instance.
   */
  get sessionId() {
    return this._webStandardTransport.sessionId;
  }
  /**
   * Sets callback for when the transport is closed.
   */
  set onclose(handler) {
    this._webStandardTransport.onclose = handler;
  }
  get onclose() {
    return this._webStandardTransport.onclose;
  }
  /**
   * Sets callback for transport errors.
   */
  set onerror(handler) {
    this._webStandardTransport.onerror = handler;
  }
  get onerror() {
    return this._webStandardTransport.onerror;
  }
  /**
   * Sets callback for incoming messages.
   */
  set onmessage(handler) {
    this._webStandardTransport.onmessage = handler;
  }
  get onmessage() {
    return this._webStandardTransport.onmessage;
  }
  /**
   * Starts the transport. This is required by the Transport interface but is a no-op
   * for the Streamable HTTP transport as connections are managed per-request.
   */
  async start() {
    return this._webStandardTransport.start();
  }
  /**
   * Closes the transport and all active connections.
   */
  async close() {
    return this._webStandardTransport.close();
  }
  /**
   * Sends a JSON-RPC message through the transport.
   */
  async send(message, options) {
    return this._webStandardTransport.send(message, options);
  }
  /**
   * Handles an incoming HTTP request, whether GET or POST.
   *
   * This method converts Node.js HTTP objects to Web Standard Request/Response
   * and delegates to the underlying WebStandardStreamableHTTPServerTransport.
   *
   * @param req - Node.js IncomingMessage, optionally with auth property from middleware
   * @param res - Node.js ServerResponse
   * @param parsedBody - Optional pre-parsed body from body-parser middleware
   */
  async handleRequest(req, res, parsedBody) {
    let authInfo = req.auth;
    await getRequestListener(async (webRequest) => this._webStandardTransport.handleRequest(webRequest, {
      authInfo,
      parsedBody
    }), { overrideGlobalObjects: !1 })(req, res);
  }
  /**
   * Close an SSE stream for a specific request, triggering client reconnection.
   * Use this to implement polling behavior during long-running operations -
   * client will reconnect after the retry interval specified in the priming event.
   */
  closeSSEStream(requestId) {
    this._webStandardTransport.closeSSEStream(requestId);
  }
  /**
   * Close the standalone GET SSE stream, triggering client reconnection.
   * Use this to implement polling behavior for server-initiated notifications.
   */
  closeStandaloneSSEStream() {
    this._webStandardTransport.closeStandaloneSSEStream();
  }
};

// src/android.js
import { execFile } from "node:child_process";
var shq = (s) => "'" + String(s).replace(/'/g, "'\\''") + "'", Android = class {
  constructor({ adbPath = "adb", serial = "", chromePackage = "com.android.chrome", port = 9222, log: log2 = () => {
  } } = {}) {
    this.adbPath = adbPath, this.serial = serial, this.chromePackage = chromePackage, this.port = port, this.log = log2;
  }
  adb(args, timeoutMs = 15e3) {
    let full = this.serial ? ["-s", this.serial, ...args] : args;
    return new Promise((resolve, reject) => {
      execFile(this.adbPath, full, { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
        err ? reject(new Error(`adb ${args.join(" ")} failed: ${(stderr || err.message).trim()}`)) : resolve(stdout.toString());
      });
    });
  }
  shell(cmd, timeoutMs) {
    return this.adb(["shell", cmd], timeoutMs);
  }
  /** (Re)create the TCP -> Chrome DevTools socket forward. Idempotent. */
  async ensureForward() {
    await this.adb(["forward", `tcp:${this.port}`, "localabstract:chrome_devtools_remote"]);
  }
  /** Currently focused app, e.g. { pkg: 'com.google.android.youtube', activity: '...' }. */
  async foreground() {
    try {
      let m = (await this.shell('dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity|ResumedActivity:" | head -n 3')).match(/ u\d+ ([\w.]+)\/([\w.$]+)/);
      if (m) return { pkg: m[1], activity: m[2] };
    } catch {
    }
    try {
      let m = (await this.shell('dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -n 2')).match(/ ([\w.]+)\/([\w.$]+)/);
      if (m) return { pkg: m[1], activity: m[2] };
    } catch {
    }
    return null;
  }
  async homePackage() {
    try {
      return ((await this.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")).trim().split(`
`).pop() || "").split("/")[0] || null;
    } catch {
      return null;
    }
  }
  /** Put the user's previous app back in front after Chrome had to be opened. */
  async restore(prev) {
    if (!(!prev || prev.pkg === this.chromePackage))
      try {
        let home = await this.homePackage();
        if (prev.pkg === home) {
          await this.shell("input keyevent KEYCODE_HOME");
          return;
        }
        await this.shell(`monkey -p ${shq(prev.pkg)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`);
      } catch (e) {
        this.log(`restore focus failed: ${e.message}`);
      }
  }
  /**
   * Chrome on Android refuses to create tabs over CDP, so a new tab is opened
   * with an Intent. This briefly shows Chrome; the previous app is restored.
   */
  async openTab(url, appId) {
    let prev = await this.foreground();
    return await this.shell(
      [
        "am start",
        "-a android.intent.action.VIEW",
        `-d ${shq(url)}`,
        `-p ${shq(this.chromePackage)}`,
        "--ez create_new_tab true",
        `--es com.android.browser.application_id ${shq(appId)}`
      ].join(" ")
    ), prev;
  }
  async startChrome() {
    let prev = await this.foreground();
    return await this.shell(`monkey -p ${shq(this.chromePackage)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`), prev;
  }
};

// src/browser.js
import fs from "node:fs";
import path from "node:path";
import { randomBytes } from "node:crypto";

// src/cdp.js
var WS = globalThis.WebSocket;
if (!WS)
  try {
    WS = (await import("ws")).default;
  } catch {
    throw new Error("WebSocket is unavailable: use Node 22+ (pkg install nodejs) or `npm i ws`.");
  }
var CdpError = class extends Error {
}, PageSession = class _PageSession {
  constructor(ws, targetId) {
    this.ws = ws, this.targetId = targetId, this.nextId = 1, this.pending = /* @__PURE__ */ new Map(), this.closed = !1, ws.addEventListener("message", (ev) => this.#onMessage(ev)), ws.addEventListener("close", () => this.#onClose()), ws.addEventListener("error", () => this.#onClose());
  }
  static open(wsUrl, targetId, timeoutMs = 1e4) {
    return new Promise((resolve, reject) => {
      let ws = new WS(wsUrl), timer = setTimeout(() => {
        try {
          ws.close();
        } catch {
        }
        reject(new CdpError(`Timed out connecting to tab ${targetId}`));
      }, timeoutMs);
      ws.addEventListener("open", () => {
        clearTimeout(timer), resolve(new _PageSession(ws, targetId));
      }), ws.addEventListener("error", (e) => {
        clearTimeout(timer), reject(new CdpError(`Cannot connect to tab ${targetId}: ${e?.message || "websocket error"}`));
      });
    });
  }
  #onMessage(ev) {
    let msg;
    try {
      msg = JSON.parse(typeof ev.data == "string" ? ev.data : ev.data.toString());
    } catch {
      return;
    }
    if (msg.id && this.pending.has(msg.id)) {
      let { resolve, reject, timer } = this.pending.get(msg.id);
      clearTimeout(timer), this.pending.delete(msg.id), msg.error ? reject(new CdpError(`${msg.error.message}${msg.error.data ? ": " + msg.error.data : ""}`)) : resolve(msg.result);
    }
  }
  #onClose() {
    if (!this.closed) {
      this.closed = !0;
      for (let { reject, timer } of this.pending.values())
        clearTimeout(timer), reject(new CdpError("Tab connection closed"));
      this.pending.clear();
    }
  }
  send(method, params = {}, timeoutMs = 3e4) {
    if (this.closed) return Promise.reject(new CdpError("Tab connection closed"));
    let id = this.nextId++;
    return new Promise((resolve, reject) => {
      let timer = setTimeout(() => {
        this.pending.delete(id), reject(new CdpError(`${method} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer }), this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  /** Evaluate an expression in the page and return its JSON value. */
  async evaluate(expression, { timeoutMs = 3e4, awaitPromise = !0, userGesture = !0 } = {}) {
    let res = await this.send(
      "Runtime.evaluate",
      { expression, returnByValue: !0, awaitPromise, userGesture },
      timeoutMs
    );
    if (res.exceptionDetails) {
      let d = res.exceptionDetails;
      throw new CdpError(`Page script error: ${d.exception?.description || d.text}`);
    }
    return res.result?.value;
  }
  close() {
    try {
      this.ws.close();
    } catch {
    }
    this.#onClose();
  }
}, DevtoolsHttp = class {
  constructor(baseUrl) {
    this.base = baseUrl.replace(/\/$/, "");
  }
  async #req(path3, method = "GET", timeoutMs = 5e3) {
    let ctrl = new AbortController(), t = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      let r = await fetch(this.base + path3, { method, signal: ctrl.signal }), text = await r.text();
      if (!r.ok) throw new CdpError(`DevTools HTTP ${r.status} for ${path3}: ${text.slice(0, 200)}`);
      try {
        return JSON.parse(text);
      } catch {
        return text;
      }
    } catch (e) {
      throw e instanceof CdpError ? e : new CdpError(`Chrome DevTools is not reachable at ${this.base} (${e.cause?.code || e.name})`);
    } finally {
      clearTimeout(t);
    }
  }
  version() {
    return this.#req("/json/version");
  }
  async pages() {
    return (await this.#req("/json/list")).filter((t) => t.type === "page");
  }
  /** Desktop Chrome only. Chrome on Android refuses to create tabs this way. */
  newTab(url) {
    return this.#req("/json/new?" + encodeURI(url), "PUT");
  }
  close(id) {
    return this.#req("/json/close/" + id);
  }
};

// src/page-scripts.js
var INTERACTIVE = [
  "a[href]",
  "button",
  "input",
  "textarea",
  "select",
  "summary",
  "label[for]",
  "[role=button]",
  "[role=link]",
  "[role=tab]",
  "[role=menuitem]",
  "[role=checkbox]",
  "[role=radio]",
  "[role=option]",
  "[role=combobox]",
  "[role=textbox]",
  "[role=switch]",
  "[role=searchbox]",
  '[contenteditable=""]',
  "[contenteditable=true]",
  "[onclick]"
].join(","), FIND = (ref2) => `document.querySelector('[data-mcp-ref="${Number(ref2)}"]')`, NOT_FOUND = (ref2) => `{ok:false,error:'Element [${Number(ref2)}] not found: the page changed. Take a new snapshot.'}`, SNAPSHOT_JS = (maxChars, maxElements) => `(() => {
  const SEL = ${JSON.stringify(INTERACTIVE)};
  const clean = (s, n = 80) => (s || '').replace(/\\s+/g, ' ').trim().slice(0, n);
  const visible = (el) => {
    if (el.checkVisibility && !el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) return false;
    const r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  };
  const name = (el) => {
    const by = el.getAttribute('aria-labelledby');
    if (by) { const t = by.split(/\\s+/).map(i => document.getElementById(i)?.innerText || '').join(' '); if (t.trim()) return clean(t); }
    if (el.getAttribute('aria-label')) return clean(el.getAttribute('aria-label'));
    if (el.labels && el.labels[0]) return clean(el.labels[0].innerText);
    const t = clean(el.innerText);
    if (t) return t;
    const img = el.querySelector && el.querySelector('img[alt]');
    return clean(el.getAttribute('placeholder') || el.getAttribute('title') || (img && img.alt) || el.getAttribute('name') || el.getAttribute('alt') || '');
  };
  document.querySelectorAll('[data-mcp-ref]').forEach(e => e.removeAttribute('data-mcp-ref'));
  const lines = [];
  let n = 0, total = 0;
  if (${maxElements} > 0) for (const el of document.querySelectorAll(SEL)) {
    if (el.type === 'hidden' || !visible(el)) continue;
    const parent = el.parentElement && el.parentElement.closest('[data-mcp-ref]');
    if (parent && (parent.tagName === 'A' || parent.tagName === 'BUTTON')) continue;
    total++;
    if (n >= ${maxElements}) continue;
    n++;
    el.setAttribute('data-mcp-ref', n);
    const tag = el.tagName.toLowerCase();
    const role = el.getAttribute('role');
    let kind = role || tag;
    if (tag === 'input') kind = 'input:' + (el.type || 'text');
    let line = '[' + n + '] ' + kind;
    const nm = name(el);
    if (nm) line += ' "' + nm + '"';
    if (tag === 'input' || tag === 'textarea') {
      if (el.type === 'password') line += el.value ? ' value=(hidden)' : ' (empty)';
      else if (el.type === 'checkbox' || el.type === 'radio') line += el.checked ? ' [checked]' : ' [unchecked]';
      else line += el.value ? ' value="' + clean(el.value, 60) + '"' : ' (empty)';
    } else if (tag === 'select') {
      line += ' selected="' + clean(el.selectedOptions[0]?.text || '', 40) + '" options=' + el.options.length;
    } else if (tag === 'a') {
      const h = el.getAttribute('href') || '';
      if (h && !h.startsWith('javascript:')) line += ' -> ' + clean(h, 70);
    }
    if (el.disabled) line += ' (disabled)';
    if (el.getAttribute('aria-expanded')) line += ' expanded=' + el.getAttribute('aria-expanded');
    lines.push(line);
  }
  let text = (document.body ? document.body.innerText : '') || '';
  text = text.replace(/[ \\t]+/g, ' ').replace(/\\n\\s*\\n+/g, '\\n').trim();
  const truncated = text.length > ${maxChars};
  return {
    url: location.href,
    title: document.title,
    scroll: Math.round(scrollY) + '/' + Math.max(0, Math.round(document.documentElement.scrollHeight - innerHeight)),
    elements: lines.join('\\n') + (total > n ? '\\n... ' + (total - n) + ' more elements (scroll or raise maxElements)' : ''),
    text: text.slice(0, ${maxChars}) + (truncated ? '\\n... [text truncated, ' + text.length + ' chars total]' : ''),
  };
})()`, CLICK_JS = (ref2) => `(() => {
  const el = ${FIND(ref2)};
  if (!el) return ${NOT_FOUND(ref2)};
  el.scrollIntoView({ block: 'center', inline: 'center' });
  if (el.tagName === 'A' && el.target === '_blank') el.target = '_self';
  const r = el.getBoundingClientRect();
  const o = { bubbles: true, cancelable: true, view: window, clientX: r.x + r.width / 2, clientY: r.y + r.height / 2 };
  try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch {}
  el.dispatchEvent(new MouseEvent('mousedown', o));
  if (el.focus) el.focus();
  try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch {}
  el.dispatchEvent(new MouseEvent('mouseup', o));
  el.click();
  return { ok: true, label: (el.innerText || el.value || el.getAttribute('aria-label') || el.tagName).trim().slice(0, 60) };
})()`, FOCUS_JS = (ref2, clear) => `(() => {
  const el = ${FIND(ref2)};
  if (!el) return ${NOT_FOUND(ref2)};
  el.scrollIntoView({ block: 'center' });
  el.focus();
  const isField = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';
  if (!isField && !el.isContentEditable) return { ok: false, error: 'Element [${Number(ref2)}] is not a text field.' };
  if (${!!clear}) {
    if (isField) {
      const proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, '');
      el.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
      el.textContent = '';
    }
  } else if (isField) {
    try { el.setSelectionRange(el.value.length, el.value.length); } catch {}
  }
  return { ok: true, password: el.type === 'password', label: (el.getAttribute('aria-label') || el.placeholder || el.name || el.tagName).slice(0, 60) };
})()`, READ_VALUE_JS = (ref2) => `(() => {
  const el = ${FIND(ref2)};
  if (!el) return null;
  return el.isContentEditable ? el.innerText : el.value;
})()`, FALLBACK_TYPE_JS = (ref2, text, clear) => `(() => {
  const el = ${FIND(ref2)};
  if (!el) return false;
  const t = ${JSON.stringify(text)};
  el.focus();
  if (el.isContentEditable) {
    if (!document.execCommand('insertText', false, t)) el.textContent = (${!!clear} ? '' : el.textContent) + t;
  } else {
    const proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, (${!!clear} ? '' : el.value) + t);
  }
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
})()`, SCROLL_JS = (direction, ref2) => `(() => {
  ${ref2 ? `const el = ${FIND(ref2)}; if (!el) return ${NOT_FOUND(ref2)}; el.scrollIntoView({ block: 'center' });` : `
  const d = ${JSON.stringify(direction)};
  const h = innerHeight * 0.85;
  if (d === 'down') scrollBy(0, h);
  else if (d === 'up') scrollBy(0, -h);
  else if (d === 'top') scrollTo(0, 0);
  else if (d === 'bottom') scrollTo(0, document.documentElement.scrollHeight);
  else return { ok: false, error: 'direction must be up, down, top or bottom' };`}
  return { ok: true, scroll: Math.round(scrollY) + '/' + Math.max(0, Math.round(document.documentElement.scrollHeight - innerHeight)) };
})()`, WAIT_JS = (text, selector) => `(() => {
  ${selector ? `if (!document.querySelector(${JSON.stringify(selector)})) return false;` : ""}
  ${text ? `if (!(document.body && document.body.innerText.toLowerCase().includes(${JSON.stringify(String(text).toLowerCase())}))) return false;` : ""}
  return true;
})()`;

// src/browser.js
var sleep = (ms) => new Promise((r) => setTimeout(r, ms)), KEYS = {
  Enter: { code: "Enter", keyCode: 13, text: "\r" },
  Tab: { code: "Tab", keyCode: 9 },
  Escape: { code: "Escape", keyCode: 27 },
  Backspace: { code: "Backspace", keyCode: 8 },
  Delete: { code: "Delete", keyCode: 46 },
  Space: { key: " ", code: "Space", keyCode: 32, text: " " },
  ArrowUp: { code: "ArrowUp", keyCode: 38 },
  ArrowDown: { code: "ArrowDown", keyCode: 40 },
  ArrowLeft: { code: "ArrowLeft", keyCode: 37 },
  ArrowRight: { code: "ArrowRight", keyCode: 39 },
  PageUp: { code: "PageUp", keyCode: 33 },
  PageDown: { code: "PageDown", keyCode: 34 },
  Home: { code: "Home", keyCode: 36 },
  End: { code: "End", keyCode: 35 }
}, Mutex = class {
  #p = Promise.resolve();
  run(fn) {
    let next = this.#p.then(fn, fn);
    return this.#p = next.catch(() => {
    }), next;
  }
}, Browser = class {
  /**
   * @param {object} o
   * @param {'android'|'desktop'} o.mode
   * @param {number} o.cdpPort
   * @param {import('./android.js').Android} [o.android]
   * @param {boolean} [o.allTabs] allow access to tabs the user opened
   * @param {string} [o.stateDir]
   */
  constructor({ mode = "android", cdpPort = 9222, android: android2, allTabs = !1, stateDir, log: log2 = () => {
  } }) {
    this.mode = mode, this.http = new DevtoolsHttp(`http://127.0.0.1:${cdpPort}`), this.android = android2, this.allTabs = allTabs, this.log = log2, this.sessions = /* @__PURE__ */ new Map(), this.mutex = new Mutex(), this.stateFile = stateDir ? path.join(stateDir, "tabs.json") : null, this.agentTabs = /* @__PURE__ */ new Set(), this.current = null, this.#loadState();
  }
  #loadState() {
    if (this.stateFile)
      try {
        let s = JSON.parse(fs.readFileSync(this.stateFile, "utf8"));
        this.agentTabs = new Set(s.agentTabs || []), this.current = s.current || null;
      } catch {
      }
  }
  #saveState() {
    if (this.stateFile)
      try {
        fs.mkdirSync(path.dirname(this.stateFile), { recursive: !0 }), fs.writeFileSync(this.stateFile, JSON.stringify({ agentTabs: [...this.agentTabs], current: this.current }));
      } catch (e) {
        this.log(`cannot save state: ${e.message}`);
      }
  }
  // ---------- connection ----------
  async ensureReady() {
    if (this.mode === "android") {
      await this.android.ensureForward();
      try {
        return await this.http.version();
      } catch {
        this.log("Chrome DevTools not reachable, starting Chrome");
        let prev = await this.android.startChrome(), lastErr;
        for (let i = 0; i < 40; i++) {
          await sleep(250);
          try {
            await this.android.ensureForward();
            let v = await this.http.version();
            return await sleep(500), await this.android.restore(prev), v;
          } catch (e) {
            lastErr = e;
          }
        }
        throw await this.android.restore(prev), new CdpError(
          `Chrome DevTools is unavailable (${lastErr?.message}). Check that "USB debugging" is ON in Developer options and that adb is connected (run: chrome-mcp status).`
        );
      }
    }
    return this.http.version();
  }
  async status() {
    let v = await this.ensureReady(), pages = await this.http.pages(), live = new Set(pages.map((p) => p.id));
    for (let id of [...this.agentTabs]) live.has(id) || this.agentTabs.delete(id);
    return this.current && !live.has(this.current) && (this.current = null), this.#saveState(), {
      browser: v.Browser,
      userAgent: v["User-Agent"],
      totalTabs: pages.length,
      agentTabs: this.agentTabs.size,
      accessToUserTabs: this.allTabs,
      currentTab: this.current
    };
  }
  // ---------- tabs ----------
  #allowed(id) {
    return this.allTabs || this.agentTabs.has(id);
  }
  async listTabs() {
    return await this.ensureReady(), (await this.http.pages()).filter((p) => this.#allowed(p.id)).map((p) => ({
      tab: p.id,
      title: p.title,
      url: p.url,
      owner: this.agentTabs.has(p.id) ? "agent" : "user",
      current: p.id === this.current
    }));
  }
  async openTab(url = "about:blank") {
    await this.ensureReady();
    let id;
    if (this.mode === "desktop")
      id = (await this.http.newTab(url)).id;
    else {
      let before = new Set((await this.http.pages()).map((p) => p.id)), appId = "acm-" + randomBytes(4).toString("hex"), prev = await this.android.openTab(url, appId);
      for (let i = 0; i < 40 && !id; i++) {
        await sleep(250);
        let fresh = (await this.http.pages().catch(() => [])).filter((p) => !before.has(p.id));
        fresh.length && (id = fresh[0].id);
      }
      if (await this.android.restore(prev), !id) throw new CdpError("Chrome did not open a new tab. Make sure Chrome is allowed to run in the background.");
    }
    this.agentTabs.add(id), this.current = id, this.#saveState();
    let s = await this.session(id);
    return await this.#waitLoad(s, 2e4), id;
  }
  async closeTab(tab2) {
    let id = await this.resolve(tab2);
    this.sessions.get(id)?.close(), this.sessions.delete(id), await this.http.close(id), this.agentTabs.delete(id), this.current === id && (this.current = null), this.#saveState();
  }
  async selectTab(tab2) {
    let id = await this.resolve(tab2);
    return this.current = id, this.#saveState(), id;
  }
  /** Accepts a full tab id or a unique prefix of it; defaults to the current tab. */
  async resolve(tab2, { create = !1 } = {}) {
    await this.ensureReady();
    let pages = await this.http.pages();
    if (!tab2) {
      if (this.current && pages.some((p) => p.id === this.current)) return this.current;
      let own = pages.find((p) => this.agentTabs.has(p.id));
      if (own)
        return this.current = own.id, own.id;
      if (create) return this.openTab("about:blank");
      throw new CdpError("No tab is open yet. Use tab_open first (or navigate, which opens one).");
    }
    let matches = pages.filter((p) => p.id === tab2 || p.id.startsWith(tab2));
    if (!matches.length) throw new CdpError(`Tab ${tab2} not found (it may have been closed). Call tabs_list.`);
    if (matches.length > 1) throw new CdpError(`Tab prefix ${tab2} is ambiguous.`);
    let id = matches[0].id;
    if (!this.#allowed(id)) throw new CdpError(`Tab ${tab2} belongs to the user; access to user tabs is disabled.`);
    return id;
  }
  async session(id) {
    let cached2 = this.sessions.get(id);
    if (cached2 && !cached2.closed) return cached2;
    let page = (await this.http.pages()).find((p) => p.id === id);
    if (!page) throw new CdpError(`Tab ${id} no longer exists.`);
    if (!page.webSocketDebuggerUrl)
      throw new CdpError("This tab is already being debugged by another client (for example chrome://inspect). Close it and retry.");
    let s = await PageSession.open(page.webSocketDebuggerUrl, id);
    return this.sessions.set(id, s), await s.send("Emulation.setFocusEmulationEnabled", { enabled: !0 }).catch(() => {
    }), s;
  }
  async #page(tab2, opts) {
    let id = await this.resolve(tab2, opts), s = await this.session(id);
    return await s.send("Page.setWebLifecycleState", { state: "active" }, 3e3).catch(() => {
    }), { id, s };
  }
  async #waitLoad(s, timeoutMs = 15e3) {
    let t0 = Date.now();
    for (await sleep(150); Date.now() - t0 < timeoutMs; ) {
      try {
        let st = await s.evaluate("document.readyState", { timeoutMs: 3e3 });
        if (st === "complete" || st === "interactive" && Date.now() - t0 > 4e3) return !0;
      } catch {
      }
      await sleep(250);
    }
    return !1;
  }
  // ---------- page operations (serialized) ----------
  op(fn) {
    return this.mutex.run(fn);
  }
  navigate(url, tab2) {
    return this.op(async () => {
      if (/^[a-z][a-z0-9+.-]*:/i.test(url) || (url = "https://" + url), !tab2 && !this.current && ![...this.agentTabs].length) {
        let id2 = await this.openTab(url);
        return this.#info(await this.session(id2), id2);
      }
      let { id, s } = await this.#page(tab2, { create: !0 }), r = await s.send("Page.navigate", { url });
      if (r.errorText) throw new CdpError(`Navigation failed: ${r.errorText}`);
      return await this.#waitLoad(s, 25e3), this.#info(s, id);
    });
  }
  history(action, tab2) {
    return this.op(async () => {
      let { id, s } = await this.#page(tab2);
      return action === "reload" ? await s.send("Page.reload", {}) : await s.evaluate(action === "back" ? "history.back()" : "history.forward()"), await sleep(400), await this.#waitLoad(s, 2e4), this.#info(s, id);
    });
  }
  /** Tag the current document so we can tell whether a new one was loaded. */
  async #mark(s) {
    let m = "acm" + randomBytes(4).toString("hex");
    return await s.evaluate(`(() => {
      window.__acmMark = '${m}'; window.__acmSubmitted = false; window.__acmLeaving = false;
      document.addEventListener('submit', () => { window.__acmSubmitted = true; }, { capture: true, once: true });
      addEventListener('beforeunload', () => { window.__acmLeaving = true; }, { once: true });
    })()`).catch(() => {
    }), m;
  }
  async #markState(s, m) {
    let r = await s.evaluate(
      `({ same: window.__acmMark === '${m}', submitted: !!window.__acmSubmitted, leaving: !!window.__acmLeaving })`
    ).catch(() => ({ same: !1 }));
    return { newDocument: !r.same, submitted: !!r.submitted, leaving: !!r.leaving };
  }
  async #info(s, id) {
    let r = await s.evaluate("({url: location.href, title: document.title})").catch(() => ({}));
    return { tab: id, ...r };
  }
  snapshot({ tab: tab2, maxChars = 6e3, maxElements = 150, textOnly = !1 } = {}) {
    return this.op(async () => {
      let { id, s } = await this.#page(tab2), r = await s.evaluate(SNAPSHOT_JS(maxChars, textOnly ? 0 : maxElements));
      return { tab: id, ...r };
    });
  }
  click(ref2, tab2) {
    return this.op(async () => {
      let { id, s } = await this.#page(tab2), mark = await this.#mark(s), r = await s.evaluate(CLICK_JS(ref2));
      if (!r.ok) throw new CdpError(r.error);
      await sleep(600), await this.#waitLoad(s, 15e3);
      let info = await this.#info(s, id);
      return { clicked: r.label, navigated: (await this.#markState(s, mark)).newDocument, ...info };
    });
  }
  type(ref2, text, { clear = !0, submit = !1, tab: tab2 } = {}) {
    return this.op(async () => {
      let { id, s } = await this.#page(tab2), f = await s.evaluate(FOCUS_JS(ref2, clear));
      if (!f.ok) throw new CdpError(f.error);
      await s.send("Input.insertText", { text }).catch(() => {
      });
      let value = await s.evaluate(READ_VALUE_JS(ref2));
      typeof value == "string" && !value.includes(text) && (await s.evaluate(FALLBACK_TYPE_JS(ref2, text, clear)), value = await s.evaluate(READ_VALUE_JS(ref2)));
      let navigated = !1;
      if (submit) {
        let mark = await this.#mark(s);
        await this.#key(s, "Enter"), await sleep(800);
        let st = await this.#markState(s, mark);
        !st.newDocument && !st.submitted && !st.leaving && await s.evaluate(
          `(()=>{const e=document.querySelector('[data-mcp-ref="${Number(ref2)}"]');const f=e&&e.form;if(!f)return false;f.requestSubmit?f.requestSubmit():f.submit();return true})()`
        ).catch(() => !1) && await sleep(800), await this.#waitLoad(s, 2e4), navigated = (await this.#markState(s, mark)).newDocument;
      }
      let shown = f.password ? "(hidden password)" : value;
      return { typedInto: f.label, value: shown, submitted: submit, navigated, ...await this.#info(s, id) };
    });
  }
  selectOption(ref2, option, tab2) {
    return this.op(async () => {
      let { s } = await this.#page(tab2), r = await s.evaluate(`(()=>{
        const e=document.querySelector('[data-mcp-ref="${Number(ref2)}"]');
        if(!e) return {ok:false,error:'Element ${Number(ref2)} not found: take a new snapshot.'};
        if(e.tagName!=='SELECT') return {ok:false,error:'Element is not a <select>.'};
        const want=${JSON.stringify(String(option))}.toLowerCase();
        const o=[...e.options].find(o=>o.value.toLowerCase()===want||o.text.trim().toLowerCase()===want)
              ||[...e.options].find(o=>o.text.toLowerCase().includes(want));
        if(!o) return {ok:false,error:'Option not found. Options: '+[...e.options].map(o=>o.text.trim()).join(' | ')};
        e.value=o.value; e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
        return {ok:true,selected:o.text.trim()};
      })()`);
      if (!r.ok) throw new CdpError(r.error);
      return r;
    });
  }
  async #key(s, key) {
    let k = KEYS[key] || (key.length === 1 ? { key, code: "", keyCode: key.toUpperCase().charCodeAt(0), text: key } : null);
    if (!k) throw new CdpError(`Unknown key ${key}. Supported: ${Object.keys(KEYS).join(", ")} or a single character.`);
    let base = { key: k.key || key, code: k.code, windowsVirtualKeyCode: k.keyCode, nativeVirtualKeyCode: k.keyCode };
    await s.send("Input.dispatchKeyEvent", { type: k.text ? "keyDown" : "rawKeyDown", ...base, text: k.text, unmodifiedText: k.text }), await s.send("Input.dispatchKeyEvent", { type: "keyUp", ...base });
  }
  pressKey(key, tab2) {
    return this.op(async () => {
      let { id, s } = await this.#page(tab2);
      return await this.#key(s, key), await sleep(400), await this.#waitLoad(s, 1e4), { pressed: key, ...await this.#info(s, id) };
    });
  }
  scroll({ direction = "down", ref: ref2, tab: tab2 } = {}) {
    return this.op(async () => {
      let { s } = await this.#page(tab2), r = await s.evaluate(SCROLL_JS(direction, ref2));
      if (!r.ok) throw new CdpError(r.error);
      return r;
    });
  }
  waitFor({ text, selector, timeoutMs = 1e4, tab: tab2 } = {}) {
    return this.op(async () => {
      let { s } = await this.#page(tab2), t0 = Date.now();
      for (; Date.now() - t0 < timeoutMs; ) {
        if (await s.evaluate(WAIT_JS(text, selector)).catch(() => !1)) return { found: !0, afterMs: Date.now() - t0 };
        await sleep(300);
      }
      return { found: !1, afterMs: Date.now() - t0 };
    });
  }
  screenshot({ fullPage = !1, tab: tab2 } = {}) {
    return this.op(async () => {
      let { s } = await this.#page(tab2);
      try {
        return (await s.send(
          "Page.captureScreenshot",
          { format: "jpeg", quality: 55, captureBeyondViewport: !!fullPage, fromSurface: !0 },
          2e4
        )).data;
      } catch (e) {
        throw new CdpError(
          `Screenshot failed (${e.message}). Chrome on Android may not render tabs while it is in the background; use snapshot (text) instead.`
        );
      }
    });
  }
  evaluate(expression, tab2) {
    return this.op(async () => {
      let { s } = await this.#page(tab2), wrapped = `(async () => { const __r = await (async () => (${expression}))(); try { return JSON.parse(JSON.stringify(__r ?? null)); } catch { return String(__r); } })()`;
      try {
        return await s.evaluate(wrapped);
      } catch (e) {
        if (/SyntaxError/.test(e.message))
          return s.evaluate(`(async () => { ${expression} })()`);
        throw e;
      }
    });
  }
};

// src/server.js
var env = process.env, cfg = {
  host: env.ACM_HOST || "127.0.0.1",
  port: Number(env.ACM_PORT || 8765),
  secret: env.ACM_SECRET || "",
  mode: env.ACM_MODE || "android",
  cdpPort: Number(env.ACM_CDP_PORT || 9222),
  allTabs: env.ACM_ALL_TABS === "1",
  chromePackage: env.ACM_CHROME_PACKAGE || "com.android.chrome",
  adb: env.ACM_ADB || "adb",
  serial: env.ACM_ADB_SERIAL || "",
  stateDir: env.ACM_STATE_DIR || path2.join(os.homedir(), ".android-chrome-mcp")
};
cfg.secret.length < 16 && (console.error("ACM_SECRET must be set (at least 16 characters). Use the chrome-mcp launcher."), process.exit(1));
var log = (...a) => console.error((/* @__PURE__ */ new Date()).toISOString(), ...a), android = cfg.mode === "android" ? new Android({ adbPath: cfg.adb, serial: cfg.serial, chromePackage: cfg.chromePackage, port: cfg.cdpPort, log }) : void 0, browser = new Browser({ mode: cfg.mode, cdpPort: cfg.cdpPort, android, allTabs: cfg.allTabs, stateDir: cfg.stateDir, log }), ok = (data) => ({ content: [{ type: "text", text: typeof data == "string" ? data : JSON.stringify(data, null, 1) }] }), fail = (e) => ({ isError: !0, content: [{ type: "text", text: String(e?.message || e) }] }), wrap = (fn) => async (args) => {
  try {
    return await fn(args || {});
  } catch (e) {
    return log("tool error:", e.message), fail(e);
  }
}, tab = external_exports.string().optional().describe("Tab id (or unique prefix). Defaults to the current tab."), ref = external_exports.number().int().positive().describe("Element number [n] from the latest snapshot");
function buildServer() {
  let server = new McpServer(
    { name: "android-chrome", version: "1.0.0" },
    {
      instructions: "Controls the user's Chrome browser on their Android phone in the background, through the DevTools protocol. Only Chrome is accessible; the screen and other apps are not. The user keeps using the phone meanwhile. Typical loop: navigate -> snapshot -> click/type by element number -> snapshot again. Element numbers change after every snapshot. Prefer snapshot over screenshot (cheaper; screenshots of a background tab may fail). The browser uses the user's real Chrome profile and logins: ask before purchases, sending messages, or other irreversible actions."
    }
  );
  return server.registerTool("browser_status", {
    title: "Browser status",
    description: "Check the connection to Chrome and show the tab counts. Call this first if something fails.",
    inputSchema: {},
    annotations: { readOnlyHint: !0 }
  }, wrap(async () => ok(await browser.status()))), server.registerTool("tabs_list", {
    title: "List tabs",
    description: "List the tabs the agent can use (its own tabs; user tabs only if the user enabled access).",
    inputSchema: {},
    annotations: { readOnlyHint: !0 }
  }, wrap(async () => ok(await browser.listTabs()))), server.registerTool("tab_open", {
    title: "Open tab",
    description: "Open a new background tab owned by the agent and make it current.",
    inputSchema: { url: external_exports.string().optional().describe("URL to open (default about:blank)") }
  }, wrap(async ({ url }) => {
    let id = await browser.openTab(url ? /^[a-z][a-z0-9+.-]*:/i.test(url) ? url : "https://" + url : "about:blank");
    return ok({ tab: id, current: !0 });
  })), server.registerTool("tab_select", {
    title: "Select tab",
    description: "Make another tab current for the following commands.",
    inputSchema: { tab: external_exports.string().describe("Tab id or unique prefix") }
  }, wrap(async ({ tab: t }) => ok({ current: await browser.selectTab(t) }))), server.registerTool("tab_close", {
    title: "Close tab",
    description: "Close a tab (defaults to the current tab).",
    inputSchema: { tab },
    annotations: { destructiveHint: !0 }
  }, wrap(async ({ tab: t }) => (await browser.closeTab(t), ok("closed")))), server.registerTool("navigate", {
    title: "Navigate",
    description: "Open a URL in the current tab (opens a tab if there is none) and wait for the page to load.",
    inputSchema: { url: external_exports.string().describe('Absolute URL or domain, e.g. "https://example.com" or "example.com"'), tab }
  }, wrap(async ({ url, tab: t }) => ok(await browser.navigate(url, t)))), server.registerTool("history", {
    title: "Back / forward / reload",
    description: "Go back, forward, or reload the page.",
    inputSchema: { action: external_exports.enum(["back", "forward", "reload"]), tab }
  }, wrap(async ({ action, tab: t }) => ok(await browser.history(action, t)))), server.registerTool("snapshot", {
    title: "Read page",
    description: "Read the current page: URL, title, numbered interactive elements ([n] used by click/type/select) and the visible text. Call after every navigation or click before acting.",
    inputSchema: {
      tab,
      maxChars: external_exports.number().int().min(200).max(6e4).optional().describe("Max characters of page text (default 6000)"),
      maxElements: external_exports.number().int().min(1).max(1e3).optional().describe("Max interactive elements (default 150)"),
      textOnly: external_exports.boolean().optional().describe("Only return the text (no element list)")
    },
    annotations: { readOnlyHint: !0 }
  }, wrap(async ({ tab: t, maxChars, maxElements, textOnly }) => {
    let r = await browser.snapshot({ tab: t, maxChars, maxElements, textOnly }), parts = [`Tab: ${r.tab}`, `URL: ${r.url}`, `Title: ${r.title}`, `Scroll: ${r.scroll}`];
    return textOnly || parts.push("", "Elements:", r.elements || "(none)"), parts.push("", "Text:", r.text || "(empty)"), ok(parts.join(`
`));
  })), server.registerTool("click", {
    title: "Click",
    description: "Click an element by its number from the latest snapshot.",
    inputSchema: { ref, tab }
  }, wrap(async ({ ref: r, tab: t }) => ok(await browser.click(r, t)))), server.registerTool("type", {
    title: "Type text",
    description: "Type text into a field by its number. Replaces the current value unless clear=false. submit=true presses Enter.",
    inputSchema: {
      ref,
      text: external_exports.string(),
      clear: external_exports.boolean().optional().describe("Clear the field first (default true)"),
      submit: external_exports.boolean().optional().describe("Press Enter / submit the form afterwards"),
      tab
    }
  }, wrap(async ({ ref: r, text, clear, submit, tab: t }) => ok(await browser.type(r, text, { clear: clear !== !1, submit: !!submit, tab: t })))), server.registerTool("select_option", {
    title: "Select option",
    description: "Choose an option of a <select> element by value or visible text.",
    inputSchema: { ref, option: external_exports.string(), tab }
  }, wrap(async ({ ref: r, option, tab: t }) => ok(await browser.selectOption(r, option, t)))), server.registerTool("press_key", {
    title: "Press key",
    description: "Press a key in the focused element: Enter, Tab, Escape, Backspace, Delete, Space, ArrowUp/Down/Left/Right, PageUp, PageDown, Home, End, or one character.",
    inputSchema: { key: external_exports.string(), tab }
  }, wrap(async ({ key, tab: t }) => ok(await browser.pressKey(key, t)))), server.registerTool("scroll", {
    title: "Scroll",
    description: "Scroll the page (up, down, top, bottom) or scroll an element into view.",
    inputSchema: {
      direction: external_exports.enum(["up", "down", "top", "bottom"]).optional(),
      ref: ref.optional(),
      tab
    }
  }, wrap(async ({ direction, ref: r, tab: t }) => ok(await browser.scroll({ direction, ref: r, tab: t })))), server.registerTool("wait_for", {
    title: "Wait for",
    description: "Wait until some text and/or a CSS selector appears on the page.",
    inputSchema: {
      text: external_exports.string().optional(),
      selector: external_exports.string().optional(),
      timeoutMs: external_exports.number().int().min(100).max(6e4).optional(),
      tab
    },
    annotations: { readOnlyHint: !0 }
  }, wrap(async ({ text, selector, timeoutMs, tab: t }) => ok(await browser.waitFor({ text, selector, timeoutMs, tab: t })))), server.registerTool("screenshot", {
    title: "Screenshot",
    description: "JPEG screenshot of the tab. Costs many tokens; use snapshot when text is enough. May fail while Chrome is in the background.",
    inputSchema: { fullPage: external_exports.boolean().optional(), tab },
    annotations: { readOnlyHint: !0 }
  }, wrap(async ({ fullPage, tab: t }) => ({
    content: [{ type: "image", data: await browser.screenshot({ fullPage, tab: t }), mimeType: "image/jpeg" }]
  }))), server.registerTool("evaluate", {
    title: "Run JavaScript",
    description: "Run JavaScript in the page and return the JSON result (expression or function body with return). For extraction that snapshot cannot do.",
    inputSchema: { expression: external_exports.string(), tab }
  }, wrap(async ({ expression, tab: t }) => {
    let r = await browser.evaluate(expression, t), s = typeof r == "string" ? r : JSON.stringify(r, null, 1);
    return ok((s ?? "undefined").slice(0, 3e4));
  })), server;
}
function setHeader(req, name, value) {
  let raw = req.rawHeaders;
  for (let i = raw.length - 2; i >= 0; i -= 2) raw[i].toLowerCase() === name && raw.splice(i, 2);
  raw.push(name, value), req.headers[name] = value;
}
var expected = Buffer.from(`/mcp/${cfg.secret}`), pathOk = (p) => {
  let b = Buffer.from(p);
  return b.length === expected.length && timingSafeEqual(b, expected);
}, httpServer = http.createServer(async (req, res) => {
  let url = new URL(req.url, "http://localhost");
  if (url.pathname === "/health") {
    res.writeHead(200, { "content-type": "text/plain" }).end("ok");
    return;
  }
  if (!pathOk(url.pathname.replace(/\/$/, ""))) {
    res.writeHead(404, { "content-type": "text/plain" }).end("not found");
    return;
  }
  if (req.method === "GET" || req.method === "DELETE") {
    res.writeHead(405, { allow: "POST" }).end();
    return;
  }
  if (req.method !== "POST") {
    res.writeHead(405, { allow: "POST" }).end();
    return;
  }
  let ua = req.headers["user-agent"] || "-";
  log(`POST accept="${req.headers.accept || ""}" type="${req.headers["content-type"] || ""}" ua="${ua.slice(0, 60)}"`), setHeader(req, "accept", "application/json, text/event-stream"), /application\/json/i.test(req.headers["content-type"] || "") || setHeader(req, "content-type", "application/json");
  try {
    let server = buildServer(), transport = new StreamableHTTPServerTransport({ sessionIdGenerator: void 0, enableJsonResponse: !0 });
    res.on("close", () => {
      transport.close(), server.close();
    }), await server.connect(transport), await transport.handleRequest(req, res);
  } catch (e) {
    log("request error:", e), res.headersSent || res.writeHead(500).end("internal error");
  }
});
httpServer.listen(cfg.port, cfg.host, () => {
  log(`android-chrome MCP listening on http://${cfg.host}:${cfg.port}/mcp/<secret> (mode=${cfg.mode}, userTabs=${cfg.allTabs})`);
});
for (let sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => httpServer.close(() => process.exit(0)));
