/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import html2canvas from 'html2canvas';
import { Observable, Subject } from 'rxjs';
import { webSocket, WebSocketSubject } from 'rxjs/webSocket';

import { Alert } from '../pojo/Alert';
import { Message } from '../pojo/Message';
import { Page } from '../pojo/Page';

const alerts_uri = '/alerts';
const alerts_clear_uri = '/alerts/clear';
const alerts_summary_uri = '/alerts/summary';
const alerts_status_uri = '/alerts/status';
const websocket_url = 'ws://localhost:1157/ws';

@Injectable({
  providedIn: 'root'
})
export class AlertService {
  // @ts-ignore
  private ws: WebSocket;
  private socket$: WebSocketSubject<any> | undefined;
  private messagesSubject: Subject<any> = new Subject<any>();

  constructor(private http: HttpClient) {}
  createObservableSocket(url: string): Observable<any> {
    this.ws = new WebSocket(url);
    return new Observable(observer => {
      this.ws.onmessage = event => observer.next(event.data); // 成功，返回数据
      this.ws.onerror = event => observer.error(event); // 失败
      this.ws.onclose = event => observer.complete(); // 完成后，要结束
    });
  }
  // connectWebSocket() {
  //   this.socket$ = webSocket(websocket_url);
  //   // 订阅主题
  //   this.socket$
  //     .multiplex(
  //       () => ({ subscribe: '/topic/screenshot' }),
  //       () => ({ unsubscribe: '/topic/screenshot' }),
  //       message => message.type === 'screenshot'
  //     )
  //     .subscribe(
  //       msg => this.handleWebSocketMessage(msg),
  //       err => console.error(err)
  //     );
  // }
  //
  // private handleWebSocketMessage(msg: any) {
  //   console.log('Received message:', msg);
  //   this.messagesSubject.next(msg);
  //   if (msg.type === 'screenshot') {
  //     this.captureAndSendScreenshot();
  //   }
  // }
  //
  // private captureAndSendScreenshot() {
  //   const element = document.body;
  //   if (element) {
  //     html2canvas(element).then(canvas => {
  //       const base64Screenshot = canvas.toDataURL('image/png');
  //       this.sendWebSocketMessage({ type: 'screenshot', data: base64Screenshot });
  //     });
  //   }
  // }
  //
  // public sendWebSocketMessage(msg: any) {
  //   // @ts-ignore
  //   this.socket$.next(msg);
  // }
  public loadAlerts(
    status: number | undefined,
    priority: number | undefined,
    content: string | undefined,
    pageIndex: number,
    pageSize: number
  ): Observable<Message<Page<Alert>>> {
    pageIndex = pageIndex ? pageIndex : 0;
    pageSize = pageSize ? pageSize : 8;
    // 注意HttpParams是不可变对象 需要保存set后返回的对象为最新对象
    let httpParams = new HttpParams();
    httpParams = httpParams.appendAll({
      sort: 'id',
      order: 'desc',
      pageIndex: pageIndex,
      pageSize: pageSize
    });
    if (status != undefined && status != 9) {
      httpParams = httpParams.append('status', status);
    }
    if (priority != undefined && priority != 9) {
      httpParams = httpParams.append('priority', priority);
    }
    if (content != undefined && content != '' && content.trim() != '') {
      httpParams = httpParams.append('content', content.trim());
    }
    const options = { params: httpParams };
    return this.http.get<Message<Page<Alert>>>(alerts_uri, options);
  }

  public deleteAlerts(alertIds: Set<number>): Observable<Message<any>> {
    let httpParams = new HttpParams();
    alertIds.forEach(alertId => {
      // 注意HttpParams是不可变对象 需要保存append后返回的对象为最新对象
      // append方法可以叠加同一key, set方法会把key之前的值覆盖只留一个key-value
      httpParams = httpParams.append('ids', alertId);
    });
    const options = { params: httpParams };
    return this.http.delete<Message<any>>(alerts_uri, options);
  }

  public clearAlerts(): Observable<Message<any>> {
    return this.http.delete<Message<any>>(alerts_clear_uri);
  }

  public applyAlertsStatus(alertIds: Set<number>, status: number): Observable<Message<any>> {
    let httpParams = new HttpParams();
    alertIds.forEach(alertId => {
      // 注意HttpParams是不可变对象 需要保存append后返回的对象为最新对象
      // append方法可以叠加同一key, set方法会把key之前的值覆盖只留一个key-value
      httpParams = httpParams.append('ids', alertId);
    });
    const options = { params: httpParams };
    return this.http.put<Message<any>>(`${alerts_status_uri}/${status}`, null, options);
  }

  public getAlertsSummary(): Observable<Message<any>> {
    return this.http.get<Message<any>>(alerts_summary_uri);
  }
}
