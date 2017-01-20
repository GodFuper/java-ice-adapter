import { RTCPeerConnection, RTCSessionDescription, RTCIceCandidate, RTCDataChannel } from 'wrtc';
import * as dgram from 'dgram';
import options from './options';
import logger from './logger';
import { EventEmitter } from 'events';

export class PeerRelay extends EventEmitter {
  peerConnection: RTCPeerConnection;
  localSocket: dgram.Socket;
  localPort: number;
  dataChannel: RTCDataChannel;
  iceConnectionState: string;
  iceGatheringState: string;
  constructor(public remoteId: number, public remoteLogin: string, public createOffer: boolean) {
    super();
    let iceServer = { urls: [`stun:${options.stun_server}`, `turn:${options.stun_server}`] };
    if (options.turn_user != '') {
      iceServer['username'] = options.turn_user;
      iceServer['credential'] = options.turn_pass;
    }

    this.peerConnection = new RTCPeerConnection({
      iceServers: [iceServer]
    });

    this.peerConnection.ondatachannel = (event) => {
      this.initDataChannel(event.channel);
    };

    this.peerConnection.onicecandidate = (candidate) => {
      if (candidate.candidate) {
        logger.debug(`PeerRelay for ${remoteLogin} received candidate ${JSON.stringify(candidate.candidate)}`);
        this.emit('iceMessage', {
          'type': 'candidate',
          'candidate': candidate.candidate
        });
      }
    }

    this.peerConnection.oniceconnectionstatechange = (event) => {
      this.iceConnectionState = this.peerConnection.iceConnectionState;
      logger.debug(`Relay for ${this.remoteLogin}(${this.remoteId}): iceConnectionState changed to ${this.iceConnectionState}`);
      this.emit('iceConnectionStateChanged', this.iceConnectionState);
    };

    this.peerConnection.onicegatheringstatechange = (event) => {
      this.iceGatheringState = this.peerConnection.iceGatheringState;
      logger.debug(`Relay for ${this.remoteLogin}(${this.remoteId}): iceGatheringState changed to ${this.iceGatheringState}`);
    };

    if (createOffer) {
      logger.info(`Relay for ${remoteLogin}(${remoteId}): create offer`);

      this.initDataChannel(this.peerConnection.createDataChannel('faf', {
        ordered: false,
        maxRetransmits: 0,
      }));

      this.peerConnection.createOffer((desc: RTCSessionDescription) => {
        this.peerConnection.setLocalDescription(
          new RTCSessionDescription(desc),
          () => {
            this.emit('iceMessage', desc);
          },
          (error) => {
            this.handleError(error);
          }
        );
      }, (error) => {
        this.handleError(error);
      });
    }

    this.localSocket = dgram.createSocket('udp4');

    this.localSocket.bind(undefined, 'localhost', () => {
      this.localPort = this.localSocket.address().port;
      logger.info(`Relay for ${this.remoteLogin}(${this.remoteId}): listening on port ${this.localPort}`);
      this.emit('localSocketListening');
    });

    this.localSocket.on('message', (msg, rinfo) => {
      if (this.dataChannel) {
        this.dataChannel.send(msg);
      }
    });

    this.localSocket.on('error', (error) => {
      logger.info(`Relay for ${this.remoteLogin}(${this.remoteId}): error in localsocket: ${JSON.stringify(error)}`);
    });
    this.localSocket.on('close', () => {
      logger.info(`Relay for ${this.remoteLogin}(${this.remoteId}): local socket closed`);
      delete this.localSocket;
    });

    logger.info(`Relay for ${remoteLogin}(${remoteId}): successfully created`);
  }

  initDataChannel(dc: RTCDataChannel) {
    dc.onopen = () => {
      this.dataChannel = dc;
      logger.info(`Relay for ${this.remoteLogin}(${this.remoteId}): data channel open`);
      this.dataChannel.onmessage = (event) => {
        if (this.localSocket) {
          this.localSocket.send(Buffer.from(event.data), options.lobby_port, 'localhost', (error, bytes) => {
            if (error) {
              logger.error(`Relay for ${this.remoteLogin}(${this.remoteId}): error sending to local socket: ${JSON.stringify(error)}`);
            }
          });
        }
      }
      this.emit('datachannelOpen');
    };
    dc.onclose = () => {
      logger.info(`Relay for ${this.remoteLogin}(${this.remoteId}): data channel close`);
      if (this.dataChannel) {
        delete this.dataChannel;
      }
    }
  }

  addIceMsg(msg: any) {
    logger.info(`Relay for ${this.remoteLogin}(${this.remoteId}): received ICE msg: ${JSON.stringify(msg)}`);

    if (msg.type == 'offer') {
      this.peerConnection.setRemoteDescription(
        new RTCSessionDescription(msg),
        () => {
          this.peerConnection.createAnswer((desc: RTCSessionDescription) => {
            this.peerConnection.setLocalDescription(
              new RTCSessionDescription(desc),
              () => {
                this.emit('iceMessage', desc);
              },
              (error) => {
                this.handleError(error);
              }
            );
          },
            (error) => {
              this.handleError(error);
            }
          );
        },
        (error) => {
          this.handleError(error);
        }
      );
    }
    else if (msg.type == 'answer') {
      this.peerConnection.setRemoteDescription(
        new RTCSessionDescription(msg),
        () => {
          logger.debug(`Relay for ${this.remoteLogin}(${this.remoteId}): set remote answer`);
        },
        (error) => {
          this.handleError(error);
        });
    }
    else if (msg.type == 'candidate') {
      this.peerConnection.addIceCandidate(msg.candidate);
    }
  }

  handleError(error) {
    logger.error(`Relay for ${this.remoteLogin}(${this.remoteId}) error: ${JSON.stringify(error)}`);
  }

  close() {
    this.peerConnection.close();
    this.localSocket.close();
  }
}