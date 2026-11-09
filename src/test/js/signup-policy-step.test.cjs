const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
for(const [file,name] of [['register.js','initialStep'],['social-signup.js','initialSocialSignupStep']]) {
    const source=readFileSync('src/main/resources/static/js/'+file,'utf8');
    const fn=source.match(new RegExp('function '+name+'\\(\\) \\{[\\s\\S]*?\\n\\s*\\}'))[0];
    for(const [field,step] of [['agreedPolicyVersionIds',1],['birthDate',1],['userEmail',2],['nickname',2],[undefined,1]]) {
        const context={$:()=>({first(){return this},data(){return field},length:0})};
        assert.equal(vm.runInNewContext(fn+';'+name+'()',context),step,file+': '+field);
    }
}
console.log('Signup policy errors reopen terms step: 10 cases passed');
