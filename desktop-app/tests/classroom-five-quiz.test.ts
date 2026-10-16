import { afterEach, describe, expect, it, vi } from 'vitest';
import { createElement, act } from 'react';
import { createRoot } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { JSDOM } from 'jsdom';
import QuizScene from '../src/classroom/QuizScene';
import type { Scene } from '../src/classroom/types';
vi.mock('../src/classroom/api',()=>({submitAnswers:vi.fn()}));
const {submitAnswers}=await import('../src/classroom/api');
afterEach(()=>{vi.unstubAllGlobals();vi.clearAllMocks();});

describe('五题型真实作答控件',()=>{
  it('判断使用对错，填空与简答呈现文本输入并明确语义评阅',()=>{
    const scene:Scene={id:'mix',title:'综合练习',type:'quiz',order:0,actions:[],content:{type:'quiz',questions:[
      {id:'j',no:1,type:'judge',question:'同样大的整体？',options:[]},
      {id:'f',no:2,type:'fill',question:'面积？',options:[]},
      {id:'s',no:3,type:'short_answer',question:'解释原因',options:[]},
    ]}};
    const dom=new JSDOM(renderToStaticMarkup(createElement(QuizScene,{courseId:'c',scene})));
    expect(dom.window.document.querySelectorAll('input[type=radio]')).toHaveLength(2);
    expect(dom.window.document.querySelector('input[aria-label="第 2 题答案"]')).not.toBeNull();
    expect(dom.window.document.querySelector('textarea[aria-label="第 3 题答案"]')).not.toBeNull();
    expect(dom.window.document.body.textContent).toContain('参考答案和要点评阅');dom.window.close();
  });
  it('未知评阅或断线后保留答案，重复点击沿用同一提交ID',async()=>{
    const dom=new JSDOM('<div id="root"></div>',{url:'https://example.test'});
    vi.stubGlobal('window',dom.window);vi.stubGlobal('document',dom.window.document);vi.stubGlobal('navigator',dom.window.navigator);
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
    const scene:Scene={id:'quiz',title:'练习',type:'quiz',order:0,actions:[],content:{type:'quiz',questions:[{id:'q',no:1,type:'judge',question:'整体相同？',options:[]}]}};
    vi.mocked(submitAnswers).mockRejectedValueOnce(new Error('本组练习暂未完成评阅，答案已保留，请稍后重试')).mockResolvedValueOnce({quizId:'8',score:1,total:1,results:[{no:1,correct:true,correctAnswer:'对',userAnswer:'对',explanation:'基准相同'}]});
    const root=createRoot(document.getElementById('root')!);
    await act(async()=>{root.render(createElement(QuizScene,{courseId:'c',scene}));});
    await act(async()=>{(document.querySelector('input[type=radio]') as HTMLInputElement).click();});
    await act(async()=>{(document.querySelector('button') as HTMLButtonElement).click();});
    expect(document.querySelector('[role=alert]')?.textContent).toContain('答案已保留');
    expect((document.querySelector('input[type=radio]') as HTMLInputElement).checked).toBe(true);
    await act(async()=>{(document.querySelector('button') as HTMLButtonElement).click();});
    const calls=vi.mocked(submitAnswers).mock.calls;
    expect(calls).toHaveLength(2);expect(calls[0][2]).toBe(calls[1][2]);expect(calls[0][3]).toEqual([{no:1,answer:'对'}]);
    expect(document.body.textContent).toContain('答对 1 道');
    await act(async()=>{root.unmount();});dom.window.close();
  });
});
