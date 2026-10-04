(function(root){'use strict';
 const labels={image:'Зображення',pins:'Контакти',gpio:'GPIO',firmware:'Прошивка',mcu:'MCU',usb:'USB'};
 class Registry{
  constructor(data){if(data?.version!==1||!Array.isArray(data.boards)||!Array.isArray(data.targets))throw Error('Невідомий реєстр плат');this.data=data;this.boards=new Map(data.boards.map(b=>[b.catalogId,b]));this.targets=new Map(data.targets.map(p=>[p.id,p]));}
  board(part){return this.boards.get(typeof part==='string'?part:part.id)||null;}
  target(part,selected){const board=this.board(part);return this.targets.get(selected||board?.profile)||null;}
  connector(part,pin,selected){const board=this.board(part),target=this.target(part,selected),map=board?.connectors.find(p=>p.connector===pin);return map?.arduino!==undefined?target?.pins.find(p=>p.arduino===map.arduino):null;}
  capabilities(part,selected){const board=this.board(part),target=this.target(part,selected);return {board,target,levels:board?.support||{image:'unavailable',pins:'unavailable',gpio:'unavailable',firmware:'unavailable',mcu:'unavailable',usb:'unavailable'},sourceVerified:!!target,evidence:target?.evidence||board?.evidence||[]};}
  validateTarget(id){const target=this.targets.get(id);if(!target)throw Error('Цільова плата ще не має підтвердженого профілю: '+id);return target;}
  matrix(){return this.data.boards.map(b=>({id:b.catalogId,title:b.title,profile:b.profile,...b.support}));}
 }
 const api={Registry,labels};if(typeof module!=='undefined')module.exports=api;root.WireBoards=api;
})(typeof globalThis!=='undefined'?globalThis:this);
